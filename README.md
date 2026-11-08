# Bolivia P2P Rates Monitor & API (BOB / USDT)

Servicio backend, API REST y portal interactivo desarrollado en Java 25 y Javalin 7, diseñado para recolectar, analizar y servir de forma transparente la cotizacion de mercado P2P de USDT en Bolivianos (BOB) desde Binance P2P hacia aplicaciones, bots y plataformas en Bolivia.

El sistema fue proyectado bajo una arquitectura austera de alta eficiencia y ultra-bajo consumo de memoria (menos de 150 MB de RAM), permitiendo su operacion ininterrumpida en la capa Always Free de Oracle Cloud Infrastructure (1 OCPU, 1 GB RAM).

---

## Reglas de Oro de Arquitectura

> 1. **Binance P2P es consultado exclusivamente por el componente recolector programado en segundo plano (`RatePollerService`).** Ninguna petición de clientes, bots o desarrolladores web genera llamadas directas hacia Binance.
> 2. **Aislamiento Absoluto de Oracle:** Ninguna petición HTTP de clientes o administradores toca jamás la base de datos remota Oracle. Todas las consultas de la API REST (`/api/v1/rates/*`) y del portal web se resuelven de forma instantánea desde la memoria RAM (Caché L1) o desde la base local SQLite.
> 3. **Presupuesto Estricto de Conexiones Oracle:** El bot opera con un máximo de 1 conexión física JDBC (`maximumPoolSize=1`) y 0 conexiones en reposo (`minimumIdle=0`, `idleTimeout=60s`), reservando al menos 29 de las 30 sesiones de Oracle Autonomous Database (Always Free) para otros proyectos del usuario.
> 4. **Retención Local Segura:** SQLite actúa como buffer operacional y nunca se vacía por completo tras sincronizar con Oracle. Se retienen permanentemente al menos los últimos 100 snapshots para servir historiales de latencia ultrabaja (`/api/v1/rates/history?limit=100`) sin depender de la red externa.

---

## Arquitectura del Sistema (Híbrida SQLite + Oracle Autonomous)

El diseño desacopla el ciclo de recolección de mercado, la atención de tráfico HTTP de alta concurrencia y la sincronización asíncrona hacia el data warehouse en Oracle Cloud mediante el patrón **Transactional Outbox**.

```mermaid
flowchart TD
    subgraph OrigenExterno ["Servicio Externo"]
        BinanceAPI["Binance P2P API (c2c friendly search)"]
    end

    subgraph BotWorker ["Worker Recolector (Poller)"]
        RatePollerService["RatePollerService (ScheduledExecutorService)"]
        BinanceP2PClient["BinanceP2PClient (Java 25 HttpClient)"]
        RateAnalysisService["RateAnalysisService (Mediana, Promedio, Filtros)"]
    end

    subgraph CapaPersistenciaLocal ["Persistencia Local y Outbox (SQLite WAL)"]
        SqliteRateRepository["SqliteRateRepository"]
        CacheMemoria["AtomicReference (Cache L1 en Memoria)"]
        SqliteDB[("SQLite WAL (database/rates.db)")]
        OutboxTable[("sync_outbox (Transactional Outbox)")]
        ApiClientRepository["SqliteApiClientRepository"]
    end

    subgraph SincronizacionOracle ["Worker Asíncrono de Sincronización Oracle"]
        OracleSyncWorker["OracleSyncWorker (Lotes asíncronos + Backoff)"]
        OraclePool["HikariCP Pool (Max: 1, Min: 0, Idle: 60s)"]
        OracleDB[("Oracle Autonomous Database (Always Free)")]
    end

    subgraph CapaSeguridad ["Seguridad, Sesiones y Rate Limiting"]
        ApiKeyService["ApiKeyService (Hash SHA-256 y Cuota RPM)"]
        AdminSessionService["AdminSessionService (Sesiones Opacas y Tokens CSRF)"]
    end

    subgraph CapaWeb ["Servidor Web Javalin 7 (Virtual Threads)"]
        RateController["RateController (REST API /api/v1/rates/*)"]
        PortalController["PortalController (Portal Publico, Wiki y HTMX)"]
        AdminController["AdminController (Dashboard Administrativo HTMX)"]
    end

    subgraph Clientes ["Consumidores"]
        AppsBots["Bots de Telegram, Apps Móviles y Servidores"]
        DesarrolladoresWeb["Desarrolladores Web y Navegadores"]
        AdminUser["Administrador del Sistema"]
    end

    %% Recoleccion programada
    RatePollerService -->|"1. Intervalo programado (ej. 600s)"| BinanceP2PClient
    BinanceP2PClient -->|"2. Petición HTTP POST asíncrona"| BinanceAPI
    BinanceAPI -->|"3. Retorna ofertas brutas BUY y SELL"| BinanceP2PClient
    BinanceP2PClient -->|"4. Mapeo a P2POffer DTOs"| RatePollerService
    RatePollerService -->|"5. Filtra, valida y calcula indicadores"| RateAnalysisService
    RateAnalysisService -->|"6. Retorna RateSnapshot consolidado"| RatePollerService
    RatePollerService -->|"7. Persiste snapshot"| SqliteRateRepository
    SqliteRateRepository -->|"8a. Actualiza caché atómica en memoria"| CacheMemoria
    SqliteRateRepository -->|"8b. Transacción atómica: Snapshot + Outbox Event"| SqliteDB
    SqliteDB --- OutboxTable

    %% Sincronizacion Outbox -> Oracle
    OracleSyncWorker -->|"9a. Lee lote de eventos pendientes"| OutboxTable
    OracleSyncWorker -->|"9b. Conexión bajo demanda (0-1)"| OraclePool
    OraclePool -->|"9c. Batch INSERT idempotente (PROCESSED_EVENTS)"| OracleDB
    OracleSyncWorker -->|"9d. Purga eventos SENT y retiene 100 snapshots"| SqliteDB

    %% Consumo API REST (100% aislado de Oracle)
    AppsBots -->|"Peticiones GET con X-API-Key"| RateController
    RateController -->|"Valida llave y cuota RPM"| ApiKeyService
    ApiKeyService -->|"Verifica hash SHA-256"| ApiClientRepository
    ApiClientRepository -->|"Lectura de cliente"| SqliteDB
    RateController -->|"Responde snapshot actual (< 1ms)"| CacheMemoria
    RateController -.->|"Consultas de historial paginado"| SqliteDB

    %% Portal Publico y Wiki
    DesarrolladoresWeb -->|"Navegación, consultas y generación de llaves"| PortalController
    PortalController -->|"Lee tasas actuales para UI"| CacheMemoria
    PortalController -->|"Genera credenciales seguras"| ApiKeyService

    %% Panel Administrativo
    AdminUser -->|"Gestión, telemetría y sincronización manual"| AdminController
    AdminController -->|"Control de sesión HTTP-only y CSRF"| AdminSessionService
    AdminController -->|"Dispara refresco con cooldown (60s)"| RatePollerService
    AdminController -->|"Dispara sync manual a Oracle"| OracleSyncWorker
```

### Componentes Principales

1. **BinanceP2PClient:** Cliente HTTP nativo (`java.net.http.HttpClient`) configurado con timeouts estrictos y cabeceras de emulacion de navegador para interactuar con la API Friendly Search de Binance P2P.
2. **RateAnalysisService:** Modulo de calculo estadistico. Realiza deduplicacion por identificador `advNo`, descarta anomalias (precios nulos, limites incoherentes donde `minAmount > maxAmount`), calcula la mediana muestral como indicador primario insensible a ofertas gancho, deriva el promedio muestral y agrupa cotizaciones por entidad financiera observada (Banco Union, BCP, BNB, Mercantil Santa Cruz, etc.).
3. **RatePollerService:** Coordinador del ciclo de recoleccion. Ejecuta tareas calendarizadas sin riesgo de solapamiento mediante atomicidad (`AtomicBoolean`). Aplica politica de resiliencia con estado `stale` si Binance presenta caidas de conectividad.
4. **SqliteRateRepository & Cache L1:** Repositorio hibrido. Mantiene una referencia atomica (`AtomicReference<RateSnapshot>`) que responde inmediatamente en memoria a los consumidores REST sin tocar el disco duro en consultas concurrentes.
5. **ApiKeyService & AdminSessionService:** Modulo de control de acceso. Implementa autenticacion mediante hash criptografico SHA-256 de API Keys, rate limiting en memoria por minuto (RPM) con `ConcurrentHashMap` y gestion de sesiones opacas con tokens anti-CSRF para el panel web.
6. **Javalin 7 con Virtual Threads:** Servidor web embebido sobre Jetty 12 configurado para despachar cada peticion entrante en un hilo virtual ligero de Java 25.

---

## Arquitectura de la Base de Datos

La persistencia de datos utiliza SQLite embebido en modo WAL (Write-Ahead Logging), administrado mediante migraciones versionadas ejecutadas al arranque del servicio (`DatabaseMigrator`).

### Diagrama Entidad-Relacion

```mermaid
erDiagram
    SCHEMA_VERSION {
        INTEGER version PK
        TEXT script_name
        TEXT applied_at
    }

    RATE_SNAPSHOTS {
        INTEGER id PK
        INTEGER cycle_id
        TEXT captured_at
        INTEGER duration_ms
        INTEGER is_stale
        TEXT status
        TEXT fiat
        TEXT asset
        TEXT buy_reference_price
        TEXT buy_average_price
        TEXT buy_median_price
        TEXT buy_min_price
        TEXT buy_max_price
        INTEGER buy_valid_count
        INTEGER buy_discarded_count
        INTEGER buy_total_received
        TEXT buy_details_json
        TEXT sell_reference_price
        TEXT sell_average_price
        TEXT sell_median_price
        TEXT sell_min_price
        TEXT sell_max_price
        INTEGER sell_valid_count
        INTEGER sell_discarded_count
        INTEGER sell_total_received
        TEXT sell_details_json
    }

    API_CLIENTS {
        INTEGER id PK
        TEXT client_id UK
        TEXT client_name
        TEXT api_key_hash
        TEXT role
        INTEGER rate_limit_rpm
        INTEGER is_active
        TEXT created_at
    }

    SYNC_OUTBOX {
        INTEGER seq_id PK
        TEXT event_id UK
        TEXT aggregate_type
        TEXT aggregate_id
        TEXT event_type
        TEXT payload_json
        TEXT status
        INTEGER attempts
        TEXT last_error
        TEXT created_at
        TEXT synced_at
    }

    SYNC_METADATA {
        TEXT meta_key PK
        TEXT meta_value
        TEXT updated_at
    }

    API_CLIENTS ||--o{ RATE_SNAPSHOTS : "autoriza_acceso_a"
    RATE_SNAPSHOTS ||--o{ SYNC_OUTBOX : "encola_evento_en"
```

### Explicacion de Tablas y Estrategia de Persistencia

#### 1. Tabla `schema_version` (Migraciones)
Controla el historial de versionado de la base de datos de manera incremental:
* `version` (INTEGER, PK): Numero correlativo de la migracion aplicada (ej. 1, 2).
* `script_name` (TEXT): Nombre exacto del archivo SQL (ej. `001_create_rate_snapshots.sql`).
* `applied_at` (TEXT): Marca temporal en formato UTC en la que se ejecuto la migracion.

#### 2. Tabla `rate_snapshots` (Historico y Metricas de Mercado)
Almacena cada toma de datos efectuada por el bot, separando con precision matematica las cotizaciones de compra y venta:
* **Identificadores y Control:**
  * `id` (INTEGER, PK): Clave primaria autoincremental.
  * `cycle_id` (INTEGER): Numero secuencial del ciclo de ejecucion del worker.
  * `captured_at` (TEXT): Timestamp ISO-8601 en UTC del momento de captura.
  * `duration_ms` (INTEGER): Tiempo total invertido en consultar Binance y procesar las muestras.
  * `is_stale` (INTEGER): Bandera booleana (0 = datos frescos, 1 = datos desactualizados por fallo de red).
  * `status` (TEXT): Estado operativo del snapshot (`OK` o `STALE`).
  * `fiat` / `asset` (TEXT): Par evaluado (por defecto `BOB` y `USDT`).
* **Metricas de Mercado de Compra (`buy_*`):**
  * `buy_reference_price`, `buy_average_price`, `buy_median_price`, `buy_min_price`, `buy_max_price`: Precios almacenados como `TEXT` para garantizar precision decimal arbitraria con `BigDecimal` sin sufrir errores de redondeo de punto flotante.
  * `buy_valid_count`, `buy_discarded_count`, `buy_total_received`: Metricas de calidad del muestreo.
  * `buy_details_json`: Documento JSON que almacena el desglose por entidad bancaria y las mejores ofertas observadas.
* **Metricas de Mercado de Venta (`sell_*`):**
  * Estructura simetrica a la de compra, preservando estadisticas y el desglose en `sell_details_json`.
* **Indices de Rendimiento:**
  * `idx_snapshots_captured_at` (DESC): Optimiza busquedas cronologicas e historiales paginados.
  * `idx_snapshots_cycle_id` (DESC): Acelera la resolucion del ciclo mas reciente.

#### 3. Tabla `api_clients` (Seguridad y Control de Acceso)
Gobierna la autorizacion de consumo para desarrolladores y administradores:
* `id` (INTEGER, PK): Clave primaria autoincremental.
* `client_id` (TEXT, UNIQUE): Slug identificador unico (ej. `client_demo_bolivia`).
* `client_name` (TEXT): Nombre descriptivo de la aplicacion o desarrollador.
* `api_key_hash` (TEXT): Hash criptografico SHA-256 en representacion hexadecimal. La llave en texto plano jamas se almacena en disco.
* `role` (TEXT): Rol de autorizacion asignado (`CONSUMER` para lectura de tasas o `ADMIN` para tareas de mantenimiento y refresco).
* `rate_limit_rpm` (INTEGER): Cuota de peticiones por minuto permitida para el cliente.
* `is_active` (INTEGER): Estado de la llave (1 = activa, 0 = revocada).
* `created_at` (TEXT): Timestamp UTC de registro del cliente.
* **Indices de Rendimiento:**
  * `idx_api_clients_hash`: Indice B-Tree para autenticacion en tiempo O(1) sobre el hash recibido.

#### 4. Tabla `sync_outbox` (Transactional Outbox Local)
Buffer local para la sincronizacion asincrona hacia Oracle Autonomous Database:
* `seq_id` (INTEGER, PK): Secuencia incremental estricta para garantizar procesamiento en orden y replay determinista.
* `event_id` (TEXT, UNIQUE): Identificador global unico estable entre reintentos (`<source_instance_id>:<event_type>:<aggregate_id>:<local_id>`).
* `aggregate_type` / `aggregate_id`: Tipo y clave de la entidad (`RATE_SNAPSHOT`, `API_CLIENT`).
* `event_type`: Operacion (`RATE_SNAPSHOT`, `API_CLIENT_UPSERT`, `API_CLIENT_DELETE`).
* `payload_json` (TEXT): Payload JSON completo del registro.
* `status` (TEXT): Estado del evento (`PENDING`, `FAILED`, `SENT`).
* `attempts` (INTEGER): Contador de reintentos ejecutados.
* `last_error` (TEXT): Ultimo mensaje de error capturado ante caidas de conectividad.
* `created_at` / `synced_at` (TEXT): Marcas temporales UTC ISO-8601 (`%Y-%m-%dT%H:%M:%fZ`).

#### 5. Tabla `sync_metadata` (Control de Estado Local)
Almacena metadatos del motor de sincronizacion:
* `source_instance_id`: Identificador unico persistente de la instancia local del bot (evita colisiones cruzadas).
* `schema_version`: Version del esquema del outbox local.

---

### Esquema Remoto en Oracle Autonomous Database (`BOLIVIA_P2P`)

En Oracle Autonomous Always Free se replican los datos historicos a largo plazo bajo un usuario aislado con cuotas seguras:
* **`PROCESSED_EVENTS`:** Tabla de idempotencia remota (`EVENT_ID VARCHAR2(120) PRIMARY KEY`). Evita inserciones duplicadas cuando ocurren caidas de red o cortes tras el `COMMIT` remoto antes del `ACK` local.
* **`RATE_SNAPSHOTS`:** Tabla historica de cotizaciones con precision matematica exacta mediante `NUMBER(24, 8)` para los campos de cotizaciones `BigDecimal`. Incluye restriccion unica `(SOURCE_INSTANCE_ID, LOCAL_SNAPSHOT_ID)`.
* **`API_CLIENTS`:** Replicacion de clientes autorizados (`CLIENT_ID VARCHAR2(64) UNIQUE`) para auditoria remota y control centralizado.

### Optimizaciones del Motor SQLite

* **Modo WAL (Write-Ahead Logging):** Configurado mediante `PRAGMA journal_mode = WAL`. Permite lecturas masivas y concurrentes sin bloquear las operaciones de escritura del hilo recolector, y viceversa.
* **Modo de Sincronizacion Normal:** `PRAGMA synchronous = NORMAL`. Reduce escrituras innecesarias en disco garantizando consistencia contra caidas de proceso.
* **Manejo de Bloqueos:** `PRAGMA busy_timeout = 5000`. Espera hasta 5 segundos en caso de contencion de cerrojos antes de arrojar un error de concurrencia.
* **Diseno Hibrido Columna-JSON:** Las metricas financieras criticas se indexan como columnas directas para analisis rapido, mientras que los arboles de detalle bancario se guardan como cadenas JSON, evitando la proliferacion de tablas hijas y operaciones JOIN costosas.

---

## Endpoints de la API y Portal Web

### 1. Endpoints de Infraestructura y Salud (Publicos)

| Metodo | Ruta | Descripcion | Autenticacion |
| :--- | :--- | :--- | :--- |
| `GET` | `/health` | Chequeo de operatividad del servicio (`status: UP`, presencia de datos y timestamp). | Ninguna |
| `GET` | `/api/v1/status` | Diagnostico detallado: ciclo actual, edad de los datos en segundos, duracion y bandera `isStale`. | Ninguna |

### 2. Portal de Desarrolladores y Wiki (Web / HTMX)

| Metodo | Ruta | Descripcion | Autenticacion |
| :--- | :--- | :--- | :--- |
| `GET` | `/` | Portal interactivo de documentacion para desarrolladores con visualizacion de cotizaciones en tiempo real. | Ninguna |
| `GET` | `/docs` | Alias directo al portal de documentacion interactiva y ejemplos de consumo. | Ninguna |
| `GET` | `/portal/rates-card` | Fragmento HTML dinamico utilizado por HTMX para refrescar la tarjeta de tasas en segundo plano. | Ninguna |
| `POST` | `/portal/keys` | Registro self-service de nuevas API Keys con proteccion por IP (maximo 5 llaves por hora por IP). | Ninguna |

### 3. Endpoints de Datos de Mercado (API REST)

Todas las rutas bajo `/api/v1/rates/*` requieren autenticacion mediante cabecera HTTP `X-API-Key` o parametro de consulta `?apiKey=`.

| Metodo | Ruta | Parametros | Descripcion |
| :--- | :--- | :--- | :--- |
| `GET` | `/api/v1/rates/latest` | Ninguno | Snapshot consolidado completo: mercados de compra y venta, referencias, bancos y mejores ofertas. |
| `GET` | `/api/v1/rates/buy` | Ninguno | Resumen exclusivo del mercado de **COMPRA** (usuario paga BOB para recibir USDT). |
| `GET` | `/api/v1/rates/sell` | Ninguno | Resumen exclusivo del mercado de **VENTA** (usuario entrega USDT para recibir BOB). |
| `GET` | `/api/v1/rates/history` | `limit` (opcional, por defecto 10, max 100) | Coleccion cronologica de los ultimos snapshots procesados y persistidos en base de datos. |
| `POST` | `/api/v1/rates/refresh` | Ninguno | Forzado de refresco manual. Requiere rol **ADMIN**. Protegido con cooldown de 60 segundos. Si el cooldown esta activo, retorna cabecera `X-Cache: HIT-COOLDOWN`. |

#### Ejemplo de Peticion REST con cURL

```bash
curl -H "X-API-Key: tu_api_key_aqui" \
  http://localhost:8080/api/v1/rates/latest
```

#### Respuestas y Codigos de Estado HTTP

* `200 OK`: Consulta resuelta exitosamente.
* `401 Unauthorized`: API Key no proporcionada, inexistente o inactiva.
* `403 Forbidden`: Intento de refresco sin credenciales de rol `ADMIN`.
* `429 Too Many Requests`: Cuota de peticiones por minuto (RPM) del cliente superada.
* `502 Bad Gateway`: Error de comunicacion al intentar contactar a Binance P2P durante un refresco manual forzado.
* `503 Service Unavailable`: Servicio en proceso de arranque antes de capturar su primer ciclo de datos.

### 4. Panel de Administracion (Web / HTMX)

| Metodo | Ruta | Descripcion | Autenticacion |
| :--- | :--- | :--- | :--- |
| `GET` | `/admin` | Vista principal del dashboard de administracion (estado del sistema, tasas y gestion de clientes). | Cookie de sesion |
| `POST` | `/admin/login` | Inicio de sesion administrativo. Genera cookie segura `HttpOnly` y token de sesion opaco. | Master API Key |
| `POST` | `/admin/logout` | Cierre de sesion y revocacion inmediata del identificador de sesion. | Sesion activa |
| `GET` | `/admin/partials/rates` | Fragmento HTMX para auto-refresco del estado de mercado en el dashboard. | Sesion activa |
| `POST` | `/admin/refresh` | Disparador de refresco manual desde el panel respetando el cooldown configurado. | Sesion activa + CSRF |
| `POST` | `/admin/clients` | Creacion manual de clientes con rol personalizado (`CONSUMER` o `ADMIN`) y cuota RPM a medida. | Sesion activa + CSRF |
| `POST` | `/admin/clients/{id}/toggle` | Habilitacion o deshabilitacion inmediata de una credencial de cliente. | Sesion activa + CSRF |
| `DELETE` | `/admin/clients/{id}` | Eliminacion definitiva de un cliente y su llave asociada. | Sesion activa + CSRF |

---

## Optimizaciones Realizadas

El proyecto implementa tecnicas de optimizacion en multiples capas para garantizar maxima estabilidad con minimos recursos de hardware:

### 1. Optimizacion de Memoria y Runtime (Java 25)

* **Virtual Threads (Project Loom):** Habilitados en Javalin (`javalinConfig.concurrency.useVirtualThreads = true`). Las operaciones de I/O bloqueante (red y disco) se atienden sin consumir hilos pesados del sistema operativo, permitiendo atender cientos de conexiones simultaneas con una huella de memoria insignificante.
* **Custom JRE Ultraligero con `jlink`:** Mediante el script `deploy/build-custom-jre.sh`, se construye una imagen de ejecucion de Java a la medida de solo ~50 MB (frente a los >400 MB de un JDK convencional). Se incluyen unicamente los modulos requeridos (`java.base`, `java.net.http`, `java.sql`, etc.), aplicando tecnicas de stripping (`--strip-debug`, `--no-man-pages`, `--compress=zip-6`).
* **Flags de Afinacion de la JVM en Produccion:** Configurados en el servicio Systemd (`deploy/bolivia-rates.service`):
  * `-Xms64m -Xmx256m`: Limita el consumo del Heap a un techo estricto de 256 MB.
  * `-XX:MaxMetaspaceSize=80m`: Restringe el espacio de metadatos de clases cargadas.
  * `-XX:+UseSerialGC`: Recolector de basura serial de minima sobrecarga de CPU y memoria, ideal para maquinas con 1 vCPU.
  * `-XX:TieredStopAtLevel=1`: Detiene la compilacion JIT en nivel 1 (compilador C1 sin profiling complejo), reduciendo el consumo de RAM del compilador y acelerando el arranque en frio.
  * `MemoryMax=450M`: Limite de control cgroup en Systemd para impedir fugas que comprometan la VM.

### 2. Optimizacion de Persistencia y Cache

* **Cache L1 Atomica en Memoria:** `SqliteRateRepository` conserva el ultimo `RateSnapshot` en un contenedor `AtomicReference`. Las peticiones a `/latest`, `/buy` y `/sell` son atendidas en submilisegundos en memoria sin acceder al disco.
* **SQLite en Modo WAL y Sincronizacion Optimizada:** Configurado con `PRAGMA journal_mode = WAL` y `PRAGMA synchronous = NORMAL`. Evita bloqueos entre hilos de lectura y escritura, reduce llamadas `fsync` a disco y eleva el rendimiento I/O.
* **Esquema Hibrido Columna-JSON:** Las metricas primarias se indexan numericamente para filtrado instantaneo, mientras que listas anidadas de bancos y ofertas se serializan en un solo campo de texto JSON, manteniendo el archivo SQLite compacto y rapido de deserializar.

### 3. Optimizacion de Red y Proteccion contra Bloqueos de Binance P2P

* **Desacoplamiento Total:** Los clientes externos nunca interactuan con la API de Binance. El trafico saliente hacia Binance se limita exclusivamente al bot interno ejecutado a intervalos de 10 minutos (600s).
* **Cooldown en Refrescos Manuales:** Protegido por una ventana minima obligatoria de 60 segundos (`REFRESH_COOLDOWN_SECONDS`). Previene ataques de denegacion de servicio y bloqueos por parte del WAF de Binance.
* **Manejo Resiliente y Estado `stale`:** Ante interrupciones de conectividad o respuestas anormales de Binance, el sistema no colapsa ni retorna errores vacios: continua entregando el ultimo snapshot valido en cache marcando explicitamente `isStale=true`.
* **Depuracion y Filtrado Estadistico:** Descarte de anuncios duplicados mediante seguimiento de `advNo`, eliminacion de anomalias con limites incoherentes y adopcion de la mediana como medida de tendencia central inmune a ofertas desproporcionadas o manipuladas.

### 4. Optimizacion de Seguridad y Control de Concurrencia

* **Rate Limiting O(1) en Memoria:** Verificacion de cuota de peticiones por minuto (RPM) implementada mediante ventanas temporales y contadores atomicos (`ClientRateTracker` con `AtomicInteger` en `ConcurrentHashMap`), evitando consultas a base de datos en cada verificacion de cuota.
* **Hash Criptografico SHA-256:** Las API Keys solo existen en memoria en el momento de su creacion. En la base de datos se almacena unicamente su representacion hash SHA-256 indizada.
* **Proteccion Anti-Abuso en el Portal:** El registro publico de API Keys incorpora una triple barrera: verificacion estricta de cabeceras Origin/Referer, limite de 5 llaves por hora por direccion IP y cooldown de 5 segundos entre solicitudes consecutivas.
* **Sesiones Administrativas Opacas y CSRF:** El dashboard web utiliza identificadores de sesion criptograficos aleatorios almacenados en memoria con expiracion automatica y tokens anti-CSRF por sesion, evitando exponer llaves maestras en cookies de navegacion.

### 5. Optimizacion de Frontend (Arquitectura Hypermedia)

* **Thymeleaf + HTMX:** Todo el portal de desarrolladores y el panel de administracion se construyen mediante renderizado del lado del servidor (SSR) y fragmentos dinamicos impulsados por HTMX. Esto prescinde completamente de frameworks SPA pesados (como React, Angular o Vue), reduciendo a cero el empaquetado de JavaScript externo y garantizando tiempos de carga casi instantaneos.

### 6. Optimizacion de Conexiones Oracle y Transactional Outbox

* **Pool HikariCP Austero (0-1 Conexiones):** Configurado con `maximumPoolSize=1`, `minimumIdle=0`, `idleTimeout=60000` y `initializationFailTimeout=-1`. El bot solo toma una conexion fisica cuando tiene lotes pendientes por sincronizar y la devuelve al pool inmediatamente tras el commit; tras 60 segundos de inactividad, la conexion se cierra por completo, liberando la sesion en Oracle Autonomous Always Free para otros proyectos.
* **Resiliencia ante Caidas de Oracle:** Si la base de datos remota no responde o hay problemas de red, los eventos se acumulan de forma segura en `sync_outbox` de SQLite con estado `FAILED` y contador de intentos. El servicio web y el bot continuan operando normalmente al 100%. Al restablecerse la conexion, el worker reanuda la sincronizacion sin duplicados ni perdidas.
* **Politica de Limpieza Dual Segura:**
  * **Outbox Confirmado:** Los eventos con estado `SENT` se eliminan de `sync_outbox` tras confirmarse el commit en Oracle y el ACK local, evitando el crecimiento indefinido del JSON en disco.
  * **Retencion Minima Local (100 Snapshots):** Los snapshots locales solo se eliminan si ya fueron sincronizados exitosamente a Oracle (`synced_at IS NOT NULL`) y si su identificador de ciclo no pertenece a los 100 snapshots mas recientes (`SQLITE_RECENT_SNAPSHOTS=100`). De esta forma, `/api/v1/rates/history` siempre responde al instante desde disco local.
* **Idempotencia Cero-Duplicados:** Cada lote inserta primero los identificadores en `PROCESSED_EVENTS` de Oracle. Reintentos tras caidas de red intermedias son absorbidos sin generar duplicados.

---

## Variables de Entorno (Configuracion)

### Configuracion General del Servicio

| Variable | Valor por Defecto | Descripcion |
| :--- | :--- | :--- |
| `PORT` | `8080` | Puerto TCP de enlace del servidor HTTP. |
| `POLL_INTERVAL_SECONDS` | `600` | Intervalo en segundos entre consultas programadas a Binance (10 minutos recomendado en produccion). |
| `REFRESH_COOLDOWN_SECONDS` | `60` | Segundos minimos de espera obligatoria entre ejecuciones de refresco manual. |
| `BINANCE_FETCH_ROWS` | `10` | Cantidad de ofertas muestreadas por cada sentido de operacion (compra y venta). |
| `FIAT_CURRENCY` | `BOB` | Moneda fiat de referencia local. |
| `ASSET_CURRENCY` | `USDT` | Criptoactivo evaluado. |
| `SQLITE_DB_PATH` | `database/rates.db` | Ruta del archivo de base de datos SQLite embebida. |
| `PUBLIC_BASE_PATH` | *(vacio)* | Prefijo de ruta base publica en despliegues tras proxy inverso (ej. `/bolivia-rates`). |
| `ADMIN_API_KEY` | *(opcional)* | Clave maestra opcional inyectada para inicializar o sincronizar la cuenta administrativa. |

### Sincronizacion con Oracle Autonomous Database (Opcional / Always Free)

| Variable | Valor por Defecto | Descripcion |
| :--- | :--- | :--- |
| `ORACLE_SYNC_ENABLED` | `false` | Habilita el worker asincrono de sincronizacion hacia Oracle Autonomous. |
| `ORACLE_JDBC_URL` | `jdbc:oracle:thin:@francodb_low` | Cadena JDBC de conexion hacia el servicio TNS de Oracle Autonomous Database. |
| `ORACLE_DB_USER` | `BOLIVIA_P2P` | Usuario dedicado en el esquema Oracle con cuota restringida. |
| `ORACLE_DB_PASSWORD` | *(vacio)* | Contrasena del usuario Oracle. |
| `ORACLE_TNS_ADMIN` | *(vacio)* | Ruta al directorio con la Wallet de Oracle descomprimida (`cwallet.sso`, `tnsnames.ora`). |
| `ORACLE_POOL_MAX_SIZE` | `1` | Maximo de conexiones fisicas JDBC simultaneas hacia Oracle (por defecto 1). |
| `ORACLE_SYNC_INTERVAL_SECONDS` | `60` | Frecuencia de ejecucion del worker de sincronizacion en segundo plano. |
| `ORACLE_SYNC_BATCH_SIZE` | `25` | Tamano maximo de eventos sincronizados por cada lote atomico. |
| `SQLITE_RECENT_SNAPSHOTS` | `100` | Cantidad minima de snapshots retenidos permanentemente en SQLite local tras purga. |

---

## Compilacion y Ejecucion

### 1. Requisitos Previos

* Java Development Kit (JDK) 25 o superior.
* Apache Maven 3.9 o superior.

### 2. Ejecutar Pruebas Unitarias y de Integracion

```bash
mvn test
```

### 3. Compilar y Empaquetar el Fat JAR

```bash
mvn clean package
```

El artefacto ejecutable se generara en la ruta:
`target/bolivia-p2p-rates-0.1.0-SNAPSHOT.jar`

### 4. Ejecutar en Entorno de Desarrollo Local

```bash
java -jar target/bolivia-p2p-rates-0.1.0-SNAPSHOT.jar
```

Para pruebas locales rapidas con intervalo de consulta cada 60 segundos:

```bash
POLL_INTERVAL_SECONDS=60 java -jar target/bolivia-p2p-rates-0.1.0-SNAPSHOT.jar
```

---

## Despliegue en Produccion (Oracle Cloud Always Free)

### 1. Construir el Custom JRE

En la maquina host de produccion (Ubuntu x86_64), ejecute el script generador de JRE minimo:

```bash
chmod +x deploy/build-custom-jre.sh
./deploy/build-custom-jre.sh
```

### 2. Configuracion con Systemd

Copie la unidad de servicio y active el daemon del sistema:

```bash
sudo cp deploy/bolivia-rates.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable bolivia-rates
sudo systemctl start bolivia-rates
```

Para monitorear los logs del servicio en tiempo real:

```bash
journalctl -u bolivia-rates -f
```

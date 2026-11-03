# Bolivia P2P Rates Monitor & API (BOB / USDT)

Bot y API REST en **Java 25** y **Javalin 7** diseñado para monitorear en tiempo real la tasa de cambio de Dólares (USDT) a Bolivianos (BOB) en **Binance P2P** y servirla a desarrolladores, webs y bots bolivianos de manera limpia, transparente y desacoplada.

Diseñado específicamente para operar con ultra-bajo consumo de memoria (`< 150 MB`), ideal para la capa gratuita de **Oracle Cloud Infrastructure (1 OCPU, 1 GB RAM)**.

---

## Regla de Oro de Arquitectura

> **Binance solo es consultado por el recolector programado en segundo plano.**  
> Si se conectan 10, 100 o 1.000 usuarios concurrentes, **ninguno genera llamadas a Binance**. La API atiende todas las peticiones consultando exclusivamente el repositorio / base de datos (`RateRepository`). Binance solo sentirá la carga de **un solo bot** a intervalos fijos.

---

## Características Principales

* **Java 25 + Virtual Threads:** Concurrencia masiva ligera en Javalin 7 sin saturar la CPU ni la memoria.
* **Metodología Robusta (docs/PLAN_BOLIVIA_P2P_RATES.md):**
  * **Mediana Muestral como Referencia Principal (`referencePrice`):** Previene distorsiones causadas por ofertas atípicas o anuncios gancho.
  * **Promedio Muestral (`averagePrice`):** Proporcionado como indicador complementario.
  * **Filtros de Calidad:** Deduplicación automática por `advNo`, descarte de anuncios con precios inválidos o límites incoherentes (`minAmount > maxAmount`).
  * **Tolerancia a Fallos y Estado `stale`:** Si ocurre un corte de red con Binance, la API sigue respondiendo con el último snapshot válido en memoria marcándolo explícitamente como `stale=true` (desactualizado).
  * **Cooldown en `/refresh`:** Evita que clientes externos saturen a Binance disparando refrescos continuos.
* **Agrupación Bancaria en Bolivia:** Cotizaciones observadas por entidad (Banco Unión, BCP, BNB, Banco Mercantil Santa Cruz, Banco FIE, Banco Ganadero, Banco Económico, SoliPagos, etc.).
* **Logs Enriquecidos:** Diagnóstico visual completo en consola por cada ciclo (`cycleId`, duración en ms, hora Bolivia, conteo de anuncios recibidos, válidos y descartados).

---

## Endpoints de la API REST

Todos los endpoints leen directamente del repositorio / base de datos local:

| Método | Endpoint | Descripción |
| :--- | :--- | :--- |
| `GET` | `/health` | Chequeo de salud del servicio (`status: UP`). |
| `GET` | `/api/v1/status` | Diagnóstico del bot: ciclo actual, edad de los datos en segundos, si están `stale`, y duración del último ciclo. |
| `GET` | `/api/v1/rates/latest` | Snapshot completo actual (mercados COMPRA y VENTA, estadísticas, bancos y anuncios). |
| `GET` | `/api/v1/rates/buy` | Solo mercado de **COMPRA** (usuario adquiere USDT pagando BOB). |
| `GET` | `/api/v1/rates/sell` | Solo mercado de **VENTA** (usuario entrega USDT recibiendo BOB). |
| `GET` | `/api/v1/rates/history?limit=10` | Historial de los últimos snapshots. |
| `POST` | `/api/v1/rates/refresh` | Refresco manual **protegido con cooldown (60s)**. Si el cooldown está activo, retorna datos de la BD sin tocar Binance. |

---

## Variables de Entorno (Configuración)

| Variable | Por Defecto | Descripción |
| :--- | :--- | :--- |
| `PORT` | `8080` | Puerto HTTP del servidor web. |
| `POLL_INTERVAL_SECONDS` | `600` | Frecuencia en segundos entre consultas automáticas a Binance (10 min en producción). |
| `REFRESH_COOLDOWN_SECONDS` | `60` | Segundos mínimos de espera entre refrescos manuales para proteger la cuota de Binance. |
| `BINANCE_FETCH_ROWS` | `10` | Cantidad de ofertas a muestrear por cada lado (compra/venta). |
| `FIAT_CURRENCY` | `BOB` | Moneda fiat local. |
| `ASSET_CURRENCY` | `USDT` | Criptoactivo consultado. |
| `SQLITE_DB_PATH` | `database/rates.db` | Ruta del archivo de base de datos SQLite embebida. |

---

## Compilación y Ejecución

### 1. Compilar y empaquetar el Fat JAR:
```bash
mvn package
```

### 2. Ejecutar las pruebas unitarias:
```bash
mvn test
```

### 3. Ejecutar la aplicación:
```bash
java -jar target/bolivia-p2p-rates-0.1.0-SNAPSHOT.jar
```

Para pruebas locales rápidas con ciclo cada 60 segundos:
```bash
POLL_INTERVAL_SECONDS=60 java -jar target/bolivia-p2p-rates-0.1.0-SNAPSHOT.jar
```

---

## Salida en Logs (Operacional)

El servicio utiliza logs concisos y estructurados, enviando toda la información histórica a la base de datos SQLite:

```text
[main] INFO dev.joracle.rates.App - Starting Bolivia P2P Rates Service (Java 25 + Javalin 7 + SQLite)...
[main] INFO dev.joracle.rates.db.DatabaseManager - Initialized DatabaseManager with URL: jdbc:sqlite:D:/Proyectos/bots/bolivia-p2p-rates/database/rates.db
[main] INFO dev.joracle.rates.db.DatabaseMigrator - Found 2 pending migration(s) to apply.
[main] INFO dev.joracle.rates.db.DatabaseMigrator - Migration 1 (001_create_rate_snapshots.sql) successfully applied.
[main] INFO dev.joracle.rates.db.DatabaseMigrator - Migration 2 (002_create_api_clients.sql) successfully applied.
[main] INFO dev.joracle.rates.App - Server started on port http://localhost:8080
[rate-poller-worker] INFO dev.joracle.rates.service.RatePollerService - Ciclo #1 completado en 1845ms [OK]. COMPRA: 12.01 BOB | VENTA: 11.96 BOB. Persistido en base de datos.
```

---

## Persistencia en SQLite y Versionado SQL (`database/`)

El almacenamiento está respaldado por SQLite embebido en modo **WAL (Write-Ahead Logging)**, garantizando que lecturas masivas de la API no se bloqueen con las escrituras del recolector.

Los cambios en la base de datos se versionan mediante scripts SQL en la carpeta `database/`:
* `001_create_rate_snapshots.sql`: Crea la tabla `rate_snapshots` e índices para histórico y consultas rápidas.
* `002_create_api_clients.sql`: Crea la tabla `api_clients` para control de API Keys y semillas iniciales.


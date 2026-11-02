# PLAN MAESTRO — BOLIVIA P2P RATES

**Proyecto:** `bolivia-p2p-rates`  
**Versión del plan:** 1.0  
**Fecha:** 2026-11-02  
**Estado actual:** prototipo local funcional; lectura de Binance P2P y visualización en logs; repositorio en memoria.  
**Objetivo final:** una API pública, fiable y austera de referencias USDT/BOB para desarrolladores bolivianos, con histórico en Oracle Autonomous AI Database Always Free, ejecutada en una VM Oracle Always Free de 1 OCPU y 1 GB RAM.

> **Regla central:** Binance solo es consultado por el recolector programado. Las solicitudes de usuarios nunca producen consultas directas a Binance. El servicio persiste datos válidos y atiende a sus consumidores mediante caché y repositorio.

---

## 1. Propósito y alcance

### 1.1 Problema

Desarrolladores, páginas web, pequeños comercios, aplicaciones y bots bolivianos necesitan una referencia programable y comprensible del mercado **USDT/BOB en Binance P2P**. Un precio aislado no basta: deben conocerse la dirección de la operación, el momento de captura, el método de cálculo y las limitaciones de los anuncios muestreados.

### 1.2 Propuesta de valor

- Entregar de manera uniforme los precios orientativos de **compra y venta de USDT en BOB**.
- Publicar cuándo se obtuvo cada referencia y si está desactualizada.
- Ofrecer conversiones y estadísticas sin aumentar las peticiones a Binance.
- Conservar un histórico propio, auditable y reproducible.
- Permitir integraciones ligeras para sitios web, bots de Telegram y servicios Java, JavaScript, Python y otros.
- Mantener la infraestructura dentro de recursos **Always Free**, sujetos a la disponibilidad y a los límites de la cuenta OCI.

### 1.3 Lo que NO es

- No es el tipo de cambio oficial USD/BOB del Banco Central de Bolivia.
- No garantiza que **1 USDT = 1 USD**, ni que el usuario pueda ejecutar una operación al precio publicado.
- No es un exchange, un procesador de pagos ni un sistema que compra o vende activos.
- No presenta una «tasa oficial de cada banco»: los bancos y métodos de pago son los **observados en los anuncios P2P**.
- No requiere credenciales de una cuenta personal de Binance para el primer alcance.

---

## 2. Línea de base: qué existe hoy

Esta sección distingue **hechos aportados** de objetivos futuros. Se basa en el `README.md`, el `App.java` y los logs compartidos en la conversación; no equivale a una auditoría del resto del código.

| Componente | Estado conocido | Observaciones |
|---|---|---|
| JDK 25 / Javalin 7.2.4 | En ejecución | Logs muestran Jetty 12.1.14 y puerto 8080. |
| `AppConfig`, `JsonConfig` | Referenciados por `App.java` | Configuración ambiental; no se revisó su implementación. |
| `BinanceP2PClient` | En uso | El servicio recibe datos de BUY y SELL, según logs; falta revisar manejo de errores. |
| `RateAnalysisService` | En uso | Se muestran promedio, mediana, mínimo, máximo y métodos de pago; verificar fórmulas. |
| `RatePollerService` | En uso | Ejecuta ciclos cada ~60 segundos en la prueba, con worker separado. |
| `RateRepository` | Abstracción existente | `InMemoryRateRepository(100)` está conectado en `App.java`. |
| `RateController` | Rutas registradas | Se anuncian las seis rutas existentes; probar códigos HTTP y JSON real. |
| Virtual threads | Configurados | `useVirtualThreads = true`; medir comportamiento antes de afirmar ventajas cuantitativas. |
| Visualización en logs | **Funcionando** | BUY/SELL con 10 muestras por lado, hora Bolivia y bancos. |
| Base de datos persistente | **No implementada** | Aplazada deliberadamente. |
| Despliegue OCI | **No iniciado** | Aplazado deliberadamente. |

### 2.1 Endpoints actuales documentados

- `GET /health`
- `GET /api/v1/rates/latest`
- `GET /api/v1/rates/buy`
- `GET /api/v1/rates/sell`
- `GET /api/v1/rates/history?limit=10`
- `POST /api/v1/rates/refresh`

### 2.2 Configuración actual documentada

| Variable | Valor indicado en README | Decisión recomendada |
|---|---:|---|
| `PORT` | `8080` | Mantener en local; configurar bind seguro en producción. |
| `POLL_INTERVAL_SECONDS` | `60` | **Cambiar por defecto a `600`** cuando termine el test rápido. |
| `BINANCE_FETCH_ROWS` | `10` | Mantener 10 por dirección durante validación. |
| `FIAT_CURRENCY` | `BOB` | Mantener. |
| `ASSET_CURRENCY` | `USDT` | Mantener. |

### 2.3 Evidencia de logs (no cotización actual)

En la prueba del **2026-11-02** se observaron, entre otros valores, compra ~**12,02 BOB/USDT**, venta ~**11,99 BOB/USDT**, 10 muestras por lado y variaciones en los métodos de pago entre capturas. Los logs demuestran que el flujo de captura y análisis se ejecuta; **no prueban** aún la exactitud de la metodología, la disponibilidad a largo plazo ni el cumplimiento de un consumo de RAM objetivo.

### 2.4 Aspectos que requieren verificación

- El README describe «promedio ponderado», pero no conocemos su fórmula ni ponderaciones.
- Los ~300 ms de arranque y `< 150 MB` de RAM son **metas declaradas**, no mediciones demostradas con los logs; el arranque observado de Javalin fue ~829 ms.
- El endpoint `POST /refresh` puede desencadenar consultas adicionales a Binance: debe protegerse antes de cualquier publicación.
- Aún no se han revisado los contratos reales de respuesta JSON ni los tests de la implementación.

---

## 3. Decisiones de arquitectura

### 3.1 Arquitectura objetivo

```text
                          ┌───────────────────┐
                          │ Binance P2P       │
                          │ USDT / BOB        │
                          └─────────┬─────────┘
                                    │
                    SOLO recolector │ BUY + SELL programados
                                    ▼
                         ┌─────────────────────┐
                         │ Java 25 / Javalin 7 │
                         │ RatePollerService   │
                         │ RateAnalysisService │
                         └─────┬─────────┬─────┘
                               │         │
                     snapshot  │         │ persistencia
                               ▼         ▼
                      ┌─────────────┐ ┌───────────────────┐
                      │ Caché RAM   │ │ Oracle Autonomous │
                      │ inmutable   │ │ Always Free       │
                      └──────┬──────┘ └─────────┬─────────┘
                             │                  │
                             └────────┬─────────┘
                                      ▼
                              ┌───────────────┐
                              │ REST API      │
                              │ RateController│
                              └───────┬───────┘
                                      ▼
                            Webs, bots, aplicaciones
```

**Durante la fase local**: `InMemoryRateRepository` sustituye a Oracle. La API y los logs deben seguir funcionando sin ningún servicio de base de datos.

### 3.2 Mantener el monolito modular

- Un solo proceso Java y un único despliegue.
- `api`: validación HTTP, contrato público, límites y errores.
- `client`: peticiones externas a Binance; conoce el protocolo, no la API pública.
- `service`: programador, reglas de cálculo y casos de uso.
- `repository`: almacenamiento en memoria hoy, Oracle después.
- `model`: tipos del dominio y DTO de API diferenciados cuando sea necesario.
- `config`: variables, validación de arranque y configuración de JSON.
- Composición manual por constructor en `App.java`, **sin introducir un contenedor de inyección de dependencias**.

### 3.3 Principios técnicos

1. La capa API **nunca llama directamente a `BinanceP2PClient`**.
2. Una captura es un trabajo independiente y programado, no consecuencia de tráfico de usuarios.
3. Solo se publica un snapshot validado; no se mezclan valores de capturas incongruentes sin identificarlos.
4. Toda cotización expone **fuente, dirección, momento de captura y metodología**.
5. Precios y montos se calculan con `BigDecimal`, no `float` ni `double`.
6. La lectura de cotizaciones actuales privilegia un snapshot inmutable en RAM; Oracle conserva la fuente persistente y el histórico.
7. Fallas de Binance no interrumpen las rutas de lectura del último snapshot: se responde con estado `stale` cuando corresponda.
8. Mantener mínimo de dependencias, conexiones e hilos reales; medir antes de optimizar.
9. **Autenticación y cuotas por API Key:** Toda aplicación o desarrollador cliente que consuma las rutas de datos (`/api/v1/*`) debe identificarse mediante una cabecera `X-API-Key`. Esto protege la VM de 1 GB RAM contra saturación o abusos de clientes, permite monitorear el consumo individual y aplicar límites de tasa (rate limiting per-client).

---

## 4. Metodología financiera y semántica de precios

### 4.1 Direcciones sin ambigüedad

| Binance `tradeType` | Perspectiva de nuestro usuario | Unidad de la cotización |
|---|---|---|
| `BUY` | Comprar 1 USDT pagando BOB | BOB que se pagan por 1 USDT |
| `SELL` | Vender 1 USDT recibiendo BOB | BOB que se reciben por 1 USDT |

**No denominar ambos sentidos «el precio del dólar» sin aclarar la operación.**

### 4.2 Precio principal recomendado

- **Referencia BUY:** mediana de precios elegibles entre los anuncios `BUY` observados.
- **Referencia SELL:** mediana de precios elegibles entre los anuncios `SELL` observados.
- Informar también `mean`, `min`, `max`, `sampleSize`, filtros aplicados y `capturedAt`.
- Mantener una medida «mejor oferta observada», separada de la mediana, si se valida que respeta importe y condiciones.
- Evitar publicar una referencia si no se alcanza un mínimo de muestras válidas; umbral inicial propuesto: **5 anuncios**, sujeto a evaluación empírica.

**Advertencia:** la mediana de 10 anuncios es una **mediana de muestra**, no una estimación garantizada de todo el mercado. El orden de anuncios, los métodos de pago y los montos pueden sesgarla.

### 4.3 Validación y filtros

- Precio positivo y con escala decimal adecuada.
- Moneda y activo coinciden con la consulta solicitada.
- `minSingleTransAmount` y máximo compatibles entre sí.
- Anuncios duplicados: deduplicar por identificador estable **si Binance lo proporciona**.
- Considerar anuncios no operables, sin liquidez o incompletos como no elegibles, según campos confirmados.
- No inventar niveles de reputación ni asumir que la tasa de finalización predice riesgo individual.
- Revisar muestras atípicas sin borrarlas silenciosamente: conservar indicadores y justificar la regla de exclusión.

### 4.4 Conversión BOB ↔ USDT

- **Comprar USDT con BOB:** `USDT estimados = BOB / tasa BUY (BOB por USDT)`.
- **Vender USDT por BOB:** `BOB estimados = USDT × tasa SELL (BOB por USDT)`.
- Las tasas inversas se obtienen de cada lado por separado. **No utilizar una sola tasa para ambos sentidos**.
- Informar redondeo, dirección, monto y carácter **indicativo** de la conversión.
- Para cotizaciones por monto, validar que la muestra tenga anuncios cuyos límites cubran la operación; no afirmar ejecución garantizada.

### 4.5 Referencias por método de pago

- Un mismo anuncio puede aceptar varios métodos; no contarlo varias veces en la estadística general.
- «Banco Unión: X» significa **precio de anuncios observados que permiten ese método**, no una tasa publicada por el banco.
- Identificar «Transferencia Bancaria» como categoría genérica y no asignarla arbitrariamente a un banco concreto.
- Evitar prometer precisión por banco cuando hay 1–2 anuncios; mostrar `sampleSize` y condición `insufficientSample`.

---

## 5. Plan por fases y puertas de calidad

### Fase 0 — Congelar y documentar la base local (estado: esencialmente realizada)

**Objetivo:** preservar lo que funciona antes de expandirlo.

**Tareas**

- [ ] Confirmar `mvn clean test` y `mvn package` desde Windows PowerShell.
- [ ] Verificar que el artefacto arranque sin ninguna base de datos.
- [ ] Capturar ejemplos reales y anonimizados de JSON BUY/SELL para pruebas reproducibles.
- [ ] Registrar en el README las rutas y variables reales, sin cambiar contratos silenciosamente.
- [ ] Crear `docs/decisions/` para decisiones técnicas breves (ADR).

**Aceptación:** arranque reproducible, `/health` y `/latest` comprobados, un ciclo de logs BUY/SELL sin excepciones.

### Fase 1 — Recolector fiable y logs útiles (prioridad INMEDIATA)

**Objetivo:** robustecer la captura **sin introducir Oracle**.

**Tareas**

- [ ] Mantener temporalmente el intervalo de 60 s solo para pruebas cortas y controladas; cambiar el default a **600 s** una vez validado.
- [ ] Ejecutar BUY y SELL desde un único coordinador; impedir capturas solapadas, incluidas las manuales.
- [ ] Registrar `cycleId`, inicio, fin, duración, HTTP status, anuncios recibidos, válidos, descartados y razones.
- [ ] Diferenciar error de red, timeout, formato inesperado, respuesta vacía, HTTP 403/429 y HTTP 5xx.
- [ ] Ante `429`: respetar `Retry-After` cuando exista; ante `403`: suspender o espaciar capturas y alertar; nunca evadir restricciones con proxies.
- [ ] Aplicar backoff limitado y sin bucles de reintentos agresivos.
- [ ] Mantener último snapshot válido si falla una captura; marcarlo desactualizado pasado un umbral configurable.
- [ ] Decidir si un ciclo requiere ambos lados válidos para publicarse como **snapshot completo**; exponer fallas parciales explícitamente.
- [ ] Conservar salida legible en logs por ahora; nunca imprimir credenciales o cuerpos completos innecesarios.
- [ ] Registrar número real de solicitudes salientes por ciclo, incluidos reintentos y `refresh`.
- [ ] Restringir `POST /api/v1/rates/refresh` a administración local o deshabilitarlo hasta contar con protección, cooldown y control de concurrencia.

**Aceptación:** una prueba con fallos simulados conserva el último valor; no se solapan ciclos; el total de llamadas está controlado; no hay ningún acceso indirecto a Binance desde endpoints públicos.

### Fase 2 — Verificar reglas de cálculo y contrato de datos

**Objetivo:** que cada número mostrado tenga un significado defendible.

**Tareas**

- [ ] Auditar `RateAnalysisService`: confirmar si el «promedio» es simple o ponderado; documentar la fórmula.
- [ ] Usar `BigDecimal` y reglas consistentes de redondeo.
- [ ] Calcular medianas correctamente para N par e impar; validar mínimos, máximos y muestras elegibles.
- [ ] Introducir metadatos: `source`, `pair`, `side`, `capturedAt`, `sampleSize`, `methodologyVersion`, `stale`.
- [ ] Separar DTO públicos de modelos crudos de Binance.
- [ ] Probar etiquetas `BUY` y `SELL` desde el punto de vista del usuario.
- [ ] Añadir pruebas con datos vacíos, anuncios duplicados, precios atípicos y métodos de pago genéricos.
- [ ] Documentar limitaciones en README y respuestas de API.

**Aceptación:** tests deterministas verifican conversiones y medianas; la API no confunde USDT/BOB con USD/BOB oficial.

### Fase 3 — API pública básica con datos en memoria

**Objetivo:** que los desarrolladores puedan integrar la API antes de añadir la base de datos.

**Mantener rutas existentes**

- `GET /health`
- `GET /api/v1/rates/latest`
- `GET /api/v1/rates/buy`
- `GET /api/v1/rates/sell`
- `GET /api/v1/rates/history?limit=N` (histórico volátil durante esta fase)

**Añadir inicialmente**

- [ ] `GET /api/v1/convert?from=BOB&to=USDT&amount=100` (con dirección explícita de operación; evitar ambigüedad).
- [ ] `GET /api/v1/market/spread`.
- [ ] `GET /api/v1/status` (edad de datos y estado del recolector).
- [ ] **Módulo de autenticación por API Key:**
  - Middleware/filtro Javalin (`beforeMatched`) para verificar cabecera `X-API-Key`.
  - Excluir `/health` de autenticación para monitores de infraestructura.
  - Proteger `/api/v1/rates/*`, `/convert` y `/status` con validación de API Key activa.
  - Asignar rol `ADMIN` para proteger endpoints privilegiados como `POST /api/v1/rates/refresh`.
  - Soporte inicial por variables de entorno (ej. `API_KEYS=key_demo:client1,key_admin:admin_user`) antes de persistencia en BD.
  - Rate limiting básico por API Key en memoria (Token Bucket / contador por minuto) para prevenir abusos a la JVM.
- [ ] Especificación OpenAPI con ejemplos y códigos de error (incluyendo 401 Unauthorized y 429 Too Many Requests).
- [ ] Límites de tamaño/rango en parámetros (`limit`, `amount`, etc.).
- [ ] TTL de caché HTTP congruente con la frescura; no prometer actualización instantánea.

**Convenciones HTTP**

- Sin snapshot inicial válido: `503 Service Unavailable` o respuesta de estado claramente no disponible.
- Con snapshot antiguo: decidir y documentar si se responde `200` con `stale=true` o error específico; no ocultar edad.
- Solicitud inválida: `400` con mensaje estable.
- Falta de API Key o clave inválida: `401 Unauthorized` (`{"error": "Unauthorized", "message": "API Key ausente o inválida"}`).
- Cuota excedida por cliente: `429 Too Many Requests` (`{"error": "Rate limit exceeded", "message": "Cuota por minuto excedida"}`).
- Operación administrativa sin privilegios: `403 Forbidden`.
- Nunca devolver `200` con una cotización ficticia.
- Mantener rutas estables dentro de `/api/v1`.

**Aceptación:** un cliente externo puede obtener y convertir cotizaciones **sin disparar nuevas solicitudes a Binance**; contrato OpenAPI verificable.

### Fase 4 — Oracle Autonomous AI Database Always Free

**Objetivo:** persistencia e histórico sin cargar la VM Java con un motor SQL.

**Condición de inicio:** fases 1–3 estables y una necesidad real de histórico duradero.

**Tareas**

- [ ] Aprovisionar **una instancia Always Free** en la región OCI elegible, comprobando límites y condiciones vigentes.
- [ ] Crear un esquema de aplicación aislado (por ejemplo, `BOLIVIA_DOLAR`) en la instancia compartida.
- [ ] Usuario de aplicación sin privilegios `ADMIN`, TLS para conexiones y controles de red disponibles.
- [ ] Implementar `OracleRateRepository implements RateRepository` sin alterar reglas de negocio.
- [ ] Crear migraciones versionadas y probarlas contra el entorno remoto de desarrollo.
- [ ] Persistir primero **snapshots agregados**; evaluar almacenar anuncios individuales solo si aportan funciones reales.
- [ ] Asegurar escrituras idempotentes mediante un identificador de captura.
- [ ] Cargar en memoria el snapshot válido más reciente al iniciar la aplicación.
- [ ] Configurar un pool JDBC pequeño (inicialmente 2 conexiones) y timeouts.
- [ ] Definir retención, exportación y recuperación; no depender únicamente del período corto de backups gestionados.
- [ ] Medir consumo real por esquema, incluyendo índices y crecimiento histórico.

**Diseño lógico mínimo (no SQL definitivo)**

| Entidad | Finalidad | Campos orientativos |
|---|---|---|
| `RATE_SNAPSHOT` | Captura agregada | ID, par, fecha UTC, estado, versión metodológica, proveedor. |
| `RATE_SIDE` | Datos BUY y SELL de una captura | Snapshot ID, dirección, mediana, media, mínimo, máximo, N válido. |
| `RATE_OFFER` *(opcional)* | Anuncios observados | Snapshot, dirección, precio, límites, métodos, identificador disponible. |
| `COLLECTOR_RUN` | Diagnóstico del recolector | Ciclo, duración, HTTP, conteos, estado, error resumido. |
| `API_CLIENT` | Clientes, credenciales y límites | ID, client_name, api_key_hash, role (CONSUMER/ADMIN), rate_limit_rpm, is_active, created_at. |

**Capacidad aproximada:** con un ciclo cada 10 min se generan **144 capturas/día** o **52.560 capturas/año**. Si se guardan 20 ofertas por ciclo serían hasta **1.051.200 filas de ofertas/año**, antes de considerar reintentos y cardinalidad real. Por eso conviene retener agregados a largo plazo y limitar el histórico de anuncios crudos.

**Aceptación:** tras reiniciar la JVM siguen accesibles la última captura y el histórico; fallo temporal de BD no dispara peticiones extra a Binance ni bloquea indefinidamente los endpoints.

### Fase 5 — Funciones bolivianas diferenciadas

**Objetivo:** ampliar valor sin convertir cada endpoint en una consulta externa.

- [ ] `GET /api/v1/rates/by-payment-method`: valores observados por método, con tamaño de muestra y advertencias.
- [ ] `GET /api/v1/quote?side=BUY&amountBob=500&paymentMethod=...`: ofertas orientativas compatibles con importe y método.
- [ ] `GET /api/v1/market/summary`: dispersión, diferencia BUY–SELL y cantidad de anuncios elegibles.
- [ ] `GET /api/v1/rates/history?from=...&to=...&interval=...`: históricos de la base.
- [ ] `GET /api/v1/rates/changes`: variación 1 h / 24 h / 7 d calculada desde nuestras capturas.
- [ ] Evaluar catálogo de métodos de pago de Binance, si está disponible y sus condiciones de uso lo permiten.
- [ ] Explorar en módulo separado precios Spot públicos (BTCUSDT, ETHUSDT) para **referencias sintéticas** BTC/BOB y ETH/BOB, distinguiéndolas de ofertas P2P directas.

**Aceptación:** nuevos endpoints derivan del repositorio/caché; los contratos distinguen claramente datos observados, agregados e inferidos.

### Fase 6 — Despliegue y operación Always Free

**Objetivo:** publicar el servicio manteniendo coste y complejidad controlados.

- [ ] Validar que la VM **Ampere A1 de 1 OCPU y 1 GB RAM** esté dentro de la asignación Always Free disponible en la cuenta.
- [ ] Usar un único fat JAR con Java 25, servicio `systemd` y reinicio controlado.
- [ ] HTTPS con proxy ligero; Javalin no expuesto sin protección directamente.
- [ ] Secretos en variables/archivo protegido y nunca en Git.
- [ ] Configurar memoria JVM tras medición (`Xmx` moderado, sin asumir que el heap es toda la RAM).
- [ ] Rate limiting por IP para proteger **nuestro** servidor, aunque no afecte la frecuencia de Binance.
- [ ] Autenticación administrativa del refresh, o endpoint eliminado de internet.
- [ ] Monitorear CPU, RSS, heap, conexiones, latencia, tamaño de histórico y edad de datos.
- [ ] Alertas de presupuesto OCI y verificación de etiquetas **Always Free** antes de crear recursos.
- [ ] Prueba de reinicio, caída de Binance, caída de Oracle, red intermitente y arranque sin snapshot.

**Aceptación:** al menos una prueba controlada de operación continua con costes verificados en OCI, cotizaciones frescas o señaladas como antiguas, sin crecimiento no acotado de memoria o logs.

---

## 6. Política de consumo de Binance

### 6.1 Presupuesto inicial

| Concepto | Valor de diseño |
|---|---:|
| Intervalo objetivo | 600 segundos / 10 minutos |
| Lados | BUY + SELL |
| Anuncios solicitados por lado | 10 |
| Solicitudes nominales por ciclo | 2 |
| Ciclos teóricos por día | 144 |
| Solicitudes nominales por día | **288** |
| Solicitudes nominales en 30 días | **8.640** |

Estas cantidades **no constituyen un límite autorizado por Binance**. Son presupuestos propios, válidos únicamente si las respuestas caben en una petición por lado y no existen reintentos, consultas manuales ni nuevas fuentes. Debe revisarse la política de acceso y redistribución antes de publicar datos comercialmente.

### 6.2 Reglas operativas

- Un solo programador coordina la obtención P2P.
- No consultar Binance desde handlers GET públicos.
- No refrescar por cada usuario ni por cada caché miss.
- Evitar consultas simultáneas entre scheduler y refresh administrativo.
- Respetar errores 403/429 y señales de limitación; no implementar evasión.
- Mantener fuente, momento, versión de metodología y estado de validez.
- Evaluar cambios de endpoint y respuesta con tests de contrato y despliegues controlados.

---

## 7. Política de almacenamiento y caché

### Hoy: memoria local

- `InMemoryRateRepository(100)` guarda un conjunto acotado de snapshots según su implementación real.
- A 10 min/ciclo, 100 capturas equivaldrían a ~**16 h 40 min** de historial, si se conserva exactamente una por ciclo.
- Al reiniciar la JVM, el histórico local puede perderse: **es comportamiento esperado en esta fase**.
- El último snapshot se publica mediante una referencia inmutable / actualización atómica.

### Después: Oracle + caché

- Oracle: historial, persistencia y recuperación después de reinicios.
- RAM: última captura para responder rápido sin ejecutar SQL por cada petición.
- Oracle: consultas paginadas de histórico y agregaciones históricas.
- Repositorio y caché mantienen semántica coherente; documentar fallo de persistencia y recuperación.
- No introducir Redis: sería otra pieza operativa sin necesidad inicial.

---

## 8. Observabilidad mínima

### 8.1 Logs por ciclo

Formato humano actual: **mantener**, pero enriquecerlo con campos consistentes.

```text
cycleId=... status=SUCCESS source=BINANCE_P2P pair=USDT/BOB
capturedAtUtc=... boliviaLocalTime=... durationMs=...
requests=2 buy.received=10 buy.valid=10 sell.received=10 sell.valid=10
BUY median=... mean=... min=... max=... sampleSize=...
SELL median=... mean=... min=... max=... sampleSize=...
spread=... stale=false methodology=v1
```

Los números son ilustrativos: no deben rellenarse si falta un dato.

### 8.2 Métricas que registrar

- `collector_success_total`, `collector_failure_total`.
- `binance_requests_total` por endpoint y código de estado.
- `last_success_age_seconds` y `last_success_timestamp`.
- Tiempo de ciclo y latencia de respuesta externa.
- Muestras recibidas, descartadas, publicadas.
- JVM RSS, heap usado, CPU y GC; latencia API P50/P95 cuando haya tráfico real.
- Con Oracle: sesiones activas, latencia SQL, fallos de escritura y tamaño del esquema.

### 8.3 Semáforo de frescura propuesto

- **Fresco:** último snapshot válido con edad hasta 10 minutos (puede ajustarse al ritmo real).
- **Retrasado:** más de 10 y hasta 20 minutos; informar explícitamente la edad.
- **Desactualizado (`stale=true`):** más de 20 minutos o dos intervalos sin captura válida.
- **No disponible:** ningún snapshot válido desde el arranque o recuperación.

Estos umbrales son propuestas de producto, no propiedades de Binance. Deben ser configurables y revisarse tras pruebas.

---

## 9. Calidad, seguridad y pruebas

### 9.1 Pruebas unitarias indispensables

- Mediana para muestra par e impar; orden de precios; redondeo.
- BUY frente a SELL: fórmulas y etiquetas correctas.
- `BigDecimal` y formato decimal en JSON.
- Filtros de importe y método de pago; mínimos y máximos.
- Casos sin anuncios y anuncios inválidos o duplicados.
- Diferencia entre precio agregado y oferta específica.

### 9.2 Pruebas de integración

- `BinanceP2PClient` con respuestas JSON guardadas como *fixtures*; no depender siempre de red real.
- `RatePollerService` con reloj y cliente simulados para asegurar ausencia de solapamientos.
- `RateController` con repositorio falso y pruebas HTTP de estados/códigos.
- Recuperación tras `timeout`, HTTP 403, 429, 500 y payload inesperado.
- Más adelante: JDBC, idempotencia y migraciones contra instancia Oracle de desarrollo.

### 9.3 Seguridad, control de acceso y responsabilidad

- **Autenticación por API Key (`X-API-Key`):**
  - Todo cliente o desarrollador externo debe registrarse para recibir una API Key (`bolivia_live_...`).
  - Cada solicitud debe incluir la cabecera `X-API-Key: <clave>`.
  - En la base de datos se almacena únicamente el hash criptográfico (SHA-256) de la clave, nunca la clave en texto claro.
  - La clave permite rastrear el uso, identificar clientes abusivos y revocar accesos sin afectar al resto del sistema.
- **Protección y Rate Limiting por Cliente:**
  - Cada API Key tiene asignado un límite de peticiones por minuto (por defecto: 60 req/min en tier estándar).
  - Un algoritmo de Token Bucket en memoria rechaza solicitudes en exceso con HTTP `429 Too Many Requests`.
  - Evita que un cliente con un bug (bucle infinito de peticiones) sature la CPU o memoria de la máquina Always Free.
- `/refresh`: operación interna protegida que requiere una clave con rol `ADMIN`, no endpoint público libre.
- Limitar tamaño de parámetros y volumen de respuestas de histórico.
- No exponer credenciales ni datos personales innecesarios de anunciantes.
- Separar datos públicos agregados de datos crudos que pudieran tener restricciones de redistribución.
- Revisar términos aplicables de Binance sobre acceso automatizado y redistribución de información antes del lanzamiento público.
- No ofrecer garantías de ejecución, liquidez, conversión ni beneficios financieros.

---

## 10. Contrato público recomendado (evolutivo)

No modificar silenciosamente el contrato de rutas ya publicadas. Definir DTO versionados y documentación OpenAPI. Ejemplo **ficticio** de respuesta `GET /api/v1/rates/latest` (solo propuesta futura):

```json
{
  "pair": "USDT/BOB",
  "source": "BINANCE_P2P",
  "methodology": "MEDIAN_VALID_OFFERS_V1",
  "buy": {
    "bobPerUsdt": "12.02",
    "sampleSize": 10
  },
  "sell": {
    "bobPerUsdt": "11.98",
    "sampleSize": 10
  },
  "capturedAt": "2026-11-02T19:46:41Z",
  "stale": false
}
```

> Los precios y el instante son **ejemplos**, no una respuesta real ni una representación del contrato actual. Elegir explícitamente si las cantidades monetarias viajan como números JSON o cadenas decimales exactas, y mantener esa decisión estable en la versión de API.

---

## 11. Priorización — qué desarrollar ahora y qué posponer

| Orden | Trabajo | Ganancia | Dependencia |
|---:|---|---|---|
| 1 | Fortalecer poller, errores, no solapamiento, logs | Fiabilidad | Ninguna |
| 2 | Pasar de 60 s a 600 s por defecto | Menos consultas externas | Pruebas cortas aprobadas |
| 3 | Revisar cálculo de mediana/promedio y `BigDecimal` | Exactitud | Fixtures de anuncios |
| 4 | Proteger o retirar `/refresh` público | Control de carga | Poller coordinado |
| 5 | Definir `capturedAt`, `stale`, metodología | Confianza API | Modelo de snapshot |
| 6 | Añadir conversiones, spread y `/status` | Utilidad para devs | Cálculos validados |
| 7 | Pruebas y OpenAPI | Facilidad de integración | Contrato estabilizado |
| 8 | Oracle Autonomous Always Free | Persistencia | MVP estable |
| 9 | Precio por monto/banco e históricos | Diferenciación | Muestras suficientes, BD |
| 10 | Despliegue público OCI, límites y métricas | Operación real | Seguridad y observabilidad |
| 11 | Spot BTC/ETH convertido a BOB | Extensión de producto | Producto principal consolidado |

### Decisiones que NO tomar todavía

- No sustituir Javalin por un framework más pesado.
- No instalar Oracle local ni crear MySQL para esta prueba.
- No agregar microservicios, Kafka o Redis.
- No usar una API key de Binance si no la requiere el alcance actual.
- No ofrecer una «tasa por banco» como si el banco la hubiese emitido.
- No habilitar refresh arbitrario desde consumidores externos.
- No incrementar masivamente `BINANCE_FETCH_ROWS` sin medir pertinencia y términos de uso.
- No comprometer objetivos de RAM o concurrencia sin una medición repetible.

---

## 12. Siguiente sprint propuesto (sin base de datos)

**Nombre:** `0.1.x — Recolector estable`  
**Alcance:** exclusivamente código Java local, logs, memoria y rutas existentes.  
**Definición de terminado:** todos los puntos siguientes comprobados.

- [ ] El servicio arranca en PowerShell con JDK 25 y Javalin 7.
- [ ] Un ciclo consulta BUY y SELL, agrega las muestras y publica snapshot completo.
- [ ] Los logs muestran duración, estado, N válido y N descartado.
- [ ] Un fallo simulado de Binance no rompe el servidor.
- [ ] Datos antiguos se identifican como tales.
- [ ] El scheduler impide trabajos concurrentes o repetidos.
- [ ] `/refresh` no permite saltarse el presupuesto de consultas.
- [ ] Se pasa la configuración por defecto a `POLL_INTERVAL_SECONDS=600`.
- [ ] `mvn test` pasa y `/health`, `/latest`, `/buy`, `/sell` responden según contrato.
- [ ] README y este plan coinciden con el comportamiento implementado.

**Comandos de validación previstos en PowerShell:**

```powershell
$env:POLL_INTERVAL_SECONDS = "600"
$env:BINANCE_FETCH_ROWS = "10"
mvn clean test
mvn package
java -jar target/bolivia-p2p-rates-0.1.0-SNAPSHOT.jar
```

En otra terminal:

```powershell
Invoke-RestMethod http://localhost:8080/health
Invoke-RestMethod http://localhost:8080/api/v1/rates/latest |
  ConvertTo-Json -Depth 20
Invoke-RestMethod http://localhost:8080/api/v1/rates/history?limit=5 |
  ConvertTo-Json -Depth 10
```

---

## 13. Riesgos y mitigaciones

| Riesgo | Impacto | Mitigación |
|---|---|---|
| Binance cambia o restringe endpoints P2P | Alto | Aislar cliente, detectar cambios, no depender de scraping HTML, degradar con `stale`. |
| Mercado con pocos anuncios representativos | Alto | Mostrar muestra/filtros, mediana, reglas explícitas y límites de interpretación. |
| API pública dispara consultas a Binance | Alto | Solo poller, refresh protegido, ninguna llamada desde GET. |
| Datos incorrectos por BUY/SELL invertidos | Alto | Tests de semántica y documentación con ejemplos. |
| Oracle no disponible temporalmente | Medio | Caché RAM y recuperación; no bloquear indefinidamente. |
| 1 GB RAM insuficiente por mala configuración | Medio | Medir RSS/heap, limitar pool, respuestas y retención. |
| Costes OCI inesperados | Alto | Crear únicamente recursos marcados Always Free, revisar panel de costes y alertas. |
| Redistribución de datos restringida | Alto | Revisar términos y obtener permiso si fuera necesario antes de API comercial. |
| Pérdida de histórico en memoria | Bajo hoy / alto luego | Aceptar en prototipo; Oracle + exportaciones en fase persistente. |

---

## 14. Indicadores de éxito

### Producto

- API con respuestas estables y documentación simple.
- Posibilidad de consultar BUY, SELL, convertir BOB↔USDT y conocer frescura de los datos.
- Referencias etiquetadas correctamente como **USDT/BOB P2P**.

### Datos

- Porcentaje de ciclos completos exitosos.
- Tiempo desde última captura válida.
- Diferencia de comportamiento entre mediana, promedio y mejor anuncio.
- Cuántos anuncios válidos sustentan cada cotización.

### Infraestructura

- Solicitudes a Binance desacopladas del tráfico de clientes.
- Consumo de memoria y CPU medido dentro de límites de la VM.
- Persistencia y recuperación probadas al introducir Oracle.
- Coste de infraestructura supervisado y mantenido en el alcance Always Free.

---

## 15. Glosario de nombres y responsabilidades

- **Par canónico:** `USDT/BOB` (precio en BOB por una unidad USDT).
- **BUY:** usuario adquiere USDT y paga BOB.
- **SELL:** usuario entrega USDT y recibe BOB.
- **Snapshot:** resultado validado de un ciclo de captura.
- **Offer:** anuncio P2P observado, no operación ejecutada.
- **Collector / Poller:** único componente que consume Binance.
- **Repository:** lectura y escritura de snapshots e histórico.
- **Rate API:** interfaz pública que consume datos locales, no Binance.
- **Stale:** dato cuya edad excede el umbral aceptable.
- **Metodología:** versión documentada del algoritmo que produjo una referencia.
- **API Key:** Identificador secreto único asignado a cada cliente o aplicación (`X-API-Key`) para autenticar peticiones, aplicar cuotas y registrar auditoría.
- **Rate Limit por Cliente:** Cuota máxima de peticiones por minuto (RPM) permitidas a cada API Key para proteger la estabilidad de la VM de 1 GB RAM.

---

## 16. Regla para futuros agentes que trabajen en el proyecto

1. **Leer primero** el README vigente, este plan, las interfaces `RateRepository` y los contratos existentes.
2. **No declarar completado** algo que solo figure en el roadmap.
3. **Mantener compatibilidad** con rutas existentes salvo versión de API o decisión registrada.
4. **No introducir Oracle** durante el sprint de estabilidad de logs.
5. **No aumentar llamadas a Binance** por añadir endpoints derivados.
6. **Verificar los cálculos** con tests y respuestas representativas antes de cambiar el precio público.
7. **Documentar decisiones** que cambien metodología, intervalo, interpretación BUY/SELL o estructura de los datos.
8. **Medir** memoria, latencia y éxito de capturas antes de afirmar propiedades de rendimiento.
9. **Preservar la filosofía Always Free**: simplicidad, dependencia mínima y costes controlados.
10. **Informar límites** de disponibilidad, precisión y permisos del proveedor externo.

---

**Próximo trabajo recomendado:** completar **Fase 1 (recolector robusto y logs)**, luego **Fase 2 (metodología/BigDecimal)**. La base de datos, el despliegue OCI y los endpoints adicionales quedan expresamente fuera del sprint actual.

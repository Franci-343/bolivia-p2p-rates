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

## Ejemplo de Salida en Logs

```text
========================================================================================
  BINANCE P2P MONITOR [USDT / BOB] - CICLO #0001 (2026-11-02 16:42:53 Hora Bolivia)
  Estado: OK | Duración: 2512 ms | Próximo ciclo en: 600 s | Datos: FRESCOS
----------------------------------------------------------------------------------------
  COMPRA (Usuario compra USDT pagando Bolivianos):
   * Referencia (Mediana) : 12.01 BOB
   * Promedio muestral    : 12.01 BOB
   * Rango observado      : [12.00 - 12.01] BOB
   * Muestra de anuncios  : 10 recibidos (10 válidos, 0 descartados)
   * Métodos observados   : Banco Económico (~12.01), Transferencia Bancaria (~12.01), Banco de Crédito (BCP) (~12.01), Banco Ganadero (~12.01), Banco Unión (~12.01)
----------------------------------------------------------------------------------------
  VENTA (Usuario vende USDT recibiendo Bolivianos):
   * Referencia (Mediana) : 11.96 BOB
   * Promedio muestral    : 11.98 BOB
   * Rango observado      : [11.96 - 12.09] BOB
   * Muestra de anuncios  : 10 recibidos (10 válidos, 0 descartados)
   * Métodos observados   : Banco Económico (~11.98), Banco FIE (~11.98), Banco Unión (~11.96), Banco Ganadero (~11.99), Banco BNB (~11.97)
========================================================================================
```

---

## Fase 2 (Oracle Autonomous Database)

La capa de datos está desacoplada mediante la interfaz [`RateRepository`](file:///D:/Proyectos/bots/bolivia-p2p-rates/src/main/java/dev/joracle/rates/repository/RateRepository.java). Cuando se disponga de la base de datos Oracle:
1. Se añadirá `OracleRateRepository implements RateRepository`.
2. En `App.java`, se sustituye `new InMemoryRateRepository(100)` por la implementación de Oracle.
3. El resto del sistema (recolector, Javalin, controladores y lógica financiera) continuará funcionando sin modificaciones.

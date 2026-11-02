# Bolivia P2P Rates Monitor & API (BOB / USDT)

Bot y API REST en **Java 25** y **Javalin 7** diseñado para monitorear en tiempo real la tasa de cambio de Dólares (USDT) a Bolivianos (BOB) en **Binance P2P** y exponerla a desarrolladores y comunidades de manera limpia y estructurada.

Diseñado específicamente para operar con ultra-bajo consumo de memoria (`< 150 MB`), ideal para la capa gratuita de **Oracle Cloud Infrastructure (1 OCPU, 1 GB RAM)**.

---

## Características Principales

* **Java 25 + Virtual Threads:** Máxima concurrencia y ligereza sin bloqueos de hilos de sistema operativo.
* **Javalin 7:** Micro-framework web de arranque instantáneo (~300 ms).
* **Consumo Nativo de Binance P2P:** Sin scrapers HTML pesados ni navegadores headless; usa `java.net.http.HttpClient` directo contra la API P2P de Binance.
* **Análisis Estadístico:**
  * Promedio ponderado y mediana (para amortiguar ofertas atípicas).
  * Rangos mínimos y máximos.
  * Agrupación y cotizaciones por banco boliviano (Banco Unión, BCP, BNB, Banco Mercantil Santa Cruz, Banco FIE, Banco Económico, SoliPagos, etc.).
* **Visualización en Logs:** Formato claro y legible en consola en cada ciclo de actualización.
* **Arquitectura Desacoplada (Spring Boot Style):**
  * Capas claras: `api`, `service`, `repository`, `client`, `model`, `config`.
  * Inyección de dependencias por constructor.
  * Patrón `RateRepository`: Actualmente usa `InMemoryRateRepository`. Listo para intercambiarse por `OracleRateRepository` cuando se aprovisione la base de datos Oracle en la Fase 2.

---

## Endpoints de la API REST

| Método | Endpoint | Descripción |
| :--- | :--- | :--- |
| `GET` | `/health` | Chequeo de salud del servicio y disponibilidad de snapshot. |
| `GET` | `/api/v1/rates/latest` | Snapshot completo actual (mercado COMPRA y VENTA, estadísticas y ofertas). |
| `GET` | `/api/v1/rates/buy` | Solo mercado de **COMPRA** (usuario adquiere USDT pagando BOB). |
| `GET` | `/api/v1/rates/sell` | Solo mercado de **VENTA** (usuario entrega USDT recibiendo BOB). |
| `GET` | `/api/v1/rates/history?limit=10` | Historial reciente de snapshots en memoria. |
| `POST` | `/api/v1/rates/refresh` | Fuerza una consulta inmediata a Binance y retorna el snapshot fresco. |

---

## Variables de Entorno (Configuración)

| Variable | Por Defecto | Descripción |
| :--- | :--- | :--- |
| `PORT` | `8080` | Puerto HTTP del servidor web. |
| `POLL_INTERVAL_SECONDS` | `60` | Frecuencia en segundos entre consultas automáticas a Binance. |
| `BINANCE_FETCH_ROWS` | `10` | Cantidad de ofertas a consultar por cada lado (compra/venta). |
| `FIAT_CURRENCY` | `BOB` | Moneda fiat local. |
| `ASSET_CURRENCY` | `USDT` | Criptoactivo consultado. |

---

## 📦 Compilación y Ejecución

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

O especificando variables de entorno:
```bash
PORT=9090 POLL_INTERVAL_SECONDS=120 java -jar target/bolivia-p2p-rates-0.1.0-SNAPSHOT.jar
```

---

## Ejemplo de Salida en Logs

```text
========================================================================================
  BINANCE P2P MONITOR [USDT / BOB] - 2026-11-02 15:44:42 (Hora Bolivia)
----------------------------------------------------------------------------------------
 [COMPRA / BUY] Usuario compra USDT pagando Bolivianos:
   * Promedio : 12.02 BOB
   * Mediana  : 12.02 BOB
   * Rango    : [12.01 - 12.02] BOB (Muestras: 10)
   * Bancos   : Banco Economico (~12.02), Banco Unión (~12.02), Banco Mercantil Santa Cruz (~12.02), Transferencia Bancaria (~12.02), Banco Ganadero (~12.02)
----------------------------------------------------------------------------------------
 [VENTA / SELL] Usuario vende USDT recibiendo Bolivianos:
   * Promedio : 11.99 BOB
   * Mediana  : 11.98 BOB
   * Rango    : [11.97 - 12.09] BOB (Muestras: 10)
   * Bancos   : Banco Economico (~11.99), Banco Unión (~11.97), Banco FIE (~11.99), Banco Mercantil Santa Cruz (~11.98), Banco Ganadero (~12.00)
========================================================================================
```

---

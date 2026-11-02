# Comandos y API Directa de Binance P2P (USDT / BOB)

Este documento contiene los comandos exactos y scripts probados que funcionan directamente contra el endpoint público de Binance P2P sin necesidad de navegadores headless ni scrapers HTML.

---

## 1. Especificación del Endpoint

* **Método:** `POST`
* **URL:** `https://p2p.binance.com/bapi/c2c/v2/friendly/c2c/adv/search`
* **Headers requeridos:**
  * `Content-Type: application/json`
  * `User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36`
  * `Accept: */*`

### Payload JSON:
```json
{
  "asset": "USDT",
  "fiat": "BOB",
  "merchantCheck": false,
  "page": 1,
  "rows": 5,
  "payTypes": [],
  "publisherType": null,
  "tradeType": "BUY"
}
```

> **Significado de `tradeType`:**
> * `"BUY"`: Anuncios donde tú como usuario **compras** USDT pagando Bolivianos (BOB).
> * `"SELL"`: Anuncios donde tú como usuario **vendes** USDT recibiendo Bolivianos (BOB).

---

## 2. Comandos cURL Funcionales

### A. Windows (CMD)
```cmd
curl.exe -s -X POST "https://p2p.binance.com/bapi/c2c/v2/friendly/c2c/adv/search" ^
  -H "Content-Type: application/json" ^
  -H "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64)" ^
  -d "{\"asset\":\"USDT\",\"fiat\":\"BOB\",\"tradeType\":\"BUY\",\"page\":1,\"rows\":5,\"payTypes\":[],\"merchantCheck\":false}"
```

### B. Windows (PowerShell)
> **Nota:** En PowerShell se debe invocar `curl.exe` explícitamente (para no llamar al alias `Invoke-WebRequest`) y escapar las comillas dobles con backtick (`` ` ``):

```powershell
curl.exe -s -X POST "https://p2p.binance.com/bapi/c2c/v2/friendly/c2c/adv/search" `
  -H "Content-Type: application/json" `
  -H "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64)" `
  -d '{"asset":"USDT","fiat":"BOB","tradeType":"BUY","page":1,"rows":5,"payTypes":[],"merchantCheck":false}'
```

### C. Linux / macOS (Bash)
```bash
curl -s -X POST "https://p2p.binance.com/bapi/c2c/v2/friendly/c2c/adv/search" \
  -H "Content-Type: application/json" \
  -H "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64)" \
  -d '{
    "asset": "USDT",
    "fiat": "BOB",
    "merchantCheck": false,
    "page": 1,
    "rows": 5,
    "payTypes": [],
    "tradeType": "BUY"
  }'
```

---

## 3. Script en Python (Sin dependencias externas, usa `urllib`)

Este script se probó en vivo y extrae tanto la compra como la venta junto con los bancos bolivianos disponibles:

```python
import json
import urllib.request

URL = "https://p2p.binance.com/bapi/c2c/v2/friendly/c2c/adv/search"
HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    "Content-Type": "application/json",
    "Accept": "*/*",
}

def consultar_binance_p2p(trade_type: str = "BUY", rows: int = 5):
    payload = {
        "asset": "USDT",
        "fiat": "BOB",
        "merchantCheck": False,
        "page": 1,
        "rows": rows,
        "payTypes": [],
        "publisherType": None,
        "tradeType": trade_type,
    }
    
    req = urllib.request.Request(
        URL,
        data=json.dumps(payload).encode("utf-8"),
        headers=HEADERS,
        method="POST",
    )

    with urllib.request.urlopen(req, timeout=10) as resp:
        data = json.loads(resp.read().decode("utf-8"))
        print(f"=== MERCADO: {trade_type} (USDT/BOB) ===")
        for item in data.get("data", []):
            adv = item["adv"]
            nick = item["advertiser"]["nickName"]
            methods = [m["tradeMethodName"] for m in adv.get("tradeMethods", [])]
            bancos = ", ".join(methods)
            precio = adv["price"]
            min_bob = adv["minSingleTransAmount"]
            max_bob = adv["dynamicMaxSingleTransAmount"]
            print(f"Precio: {precio} BOB | Límites: {min_bob} - {max_bob} BOB | Vendedor: {nick} | Bancos: {bancos}")

if __name__ == "__main__":
    consultar_binance_p2p("BUY")
    print()
    consultar_binance_p2p("SELL")
```

---

## 4. Campos Clave de la Respuesta JSON

| Campo | Ruta en JSON | Descripción |
| :--- | :--- | :--- |
| **Precio** | `data[].adv.price` | Cotización por unidad en Bolivianos (ej. `"12.01"`). |
| **Límite Mínimo** | `data[].adv.minSingleTransAmount` | Monto mínimo en BOB que acepta la orden. |
| **Límite Máximo** | `data[].adv.dynamicMaxSingleTransAmount` | Monto máximo en BOB que permite operar. |
| **Métodos de Pago** | `data[].adv.tradeMethods[].tradeMethodName` | Bancos soportados (Banco Unión, BNB, BCP, SoliPagos, etc.). |
| **Anunciante** | `data[].advertiser.nickName` | Nombre público del comerciante. |
| **Tasa de éxito** | `data[].advertiser.monthFinishRate` | Porcentaje de órdenes completadas en los últimos 30 días. |


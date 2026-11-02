package dev.joracle.rates.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Payment method item in Binance P2P advertisement.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BinanceTradeMethod(
        String payType,
        String identifier,
        String tradeMethodName,
        String tradeMethodShortName
) {
}

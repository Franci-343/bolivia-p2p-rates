package dev.joracle.rates.client.dto;

import java.util.List;

/**
 * Payload sent to Binance P2P Friendly Search endpoint.
 */
public record BinanceSearchRequest(
        String asset,
        String fiat,
        String tradeType,
        int page,
        int rows,
        List<String> payTypes,
        String publisherType,
        boolean merchantCheck
) {
    public static BinanceSearchRequest of(String asset, String fiat, String tradeType, int rows) {
        return new BinanceSearchRequest(
                asset,
                fiat,
                tradeType,
                1,
                rows,
                List.of(),
                null,
                false
        );
    }
}

package dev.joracle.rates.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Envelope response from Binance P2P search API.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BinanceSearchResponse(
        String code,
        String message,
        String messageDetail,
        List<BinanceAdvItem> data,
        Integer total,
        Boolean success
) {
    public boolean isSuccessful() {
        return Boolean.TRUE.equals(success) && "000000".equals(code) && data != null;
    }
}

package dev.joracle.rates.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Advertisement details from Binance P2P.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BinanceAdv(
        String advNo,
        String tradeType,
        String asset,
        String fiatUnit,
        String price,
        String surplusAmount,
        String tradableQuantity,
        String minSingleTransAmount,
        String dynamicMaxSingleTransAmount,
        List<BinanceTradeMethod> tradeMethods
) {
}

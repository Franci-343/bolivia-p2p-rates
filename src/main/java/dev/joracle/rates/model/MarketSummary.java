package dev.joracle.rates.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Aggregated market statistics for a specific trade type (BUY or SELL).
 */
public record MarketSummary(
        TradeType tradeType,
        String fiat,
        String asset,
        BigDecimal averagePrice,
        BigDecimal medianPrice,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        int sampleCount,
        List<BankRateSummary> bankSummaries,
        List<P2POffer> topOffers
) {
}

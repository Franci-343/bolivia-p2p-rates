package dev.joracle.rates.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Aggregated market statistics for a specific trade type (BUY or SELL).
 * Follows the methodology of PLAN_BOLIVIA_P2P_RATES.md:
 * - referencePrice: sample median (primary robust metric)
 * - averagePrice: sample mean
 * - tracks received, valid, and discarded offer counts
 */
public record MarketSummary(
        TradeType tradeType,
        String fiat,
        String asset,
        BigDecimal referencePrice,
        BigDecimal averagePrice,
        BigDecimal medianPrice,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        int totalReceived,
        int validCount,
        int discardedCount,
        List<BankRateSummary> bankSummaries,
        List<P2POffer> topOffers
) {
    /**
     * Backward-compatible alias for validCount.
     */
    public int sampleCount() {
        return validCount;
    }
}

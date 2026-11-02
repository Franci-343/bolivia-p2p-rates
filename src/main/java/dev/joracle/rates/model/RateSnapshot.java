package dev.joracle.rates.model;

import java.time.Instant;

/**
 * Complete snapshot capturing both BUY and SELL market conditions at a point in time.
 */
public record RateSnapshot(
        Instant timestamp,
        MarketSummary buyMarket,
        MarketSummary sellMarket
) {
}

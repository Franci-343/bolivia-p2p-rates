package dev.joracle.rates.model;

import java.time.Instant;

/**
 * Complete snapshot capturing both BUY and SELL market conditions at a point in time.
 * Includes lifecycle metadata: cycleId, execution duration, and stale flag.
 */
public record RateSnapshot(
        long cycleId,
        Instant timestamp,
        long durationMs,
        boolean stale,
        String status,
        MarketSummary buyMarket,
        MarketSummary sellMarket
) {
    /**
     * Creates a stale copy of this snapshot when a background fetch cycle encounters a transient failure.
     */
    public RateSnapshot markStale() {
        return new RateSnapshot(
                this.cycleId,
                this.timestamp,
                this.durationMs,
                true,
                "STALE",
                this.buyMarket,
                this.sellMarket
        );
    }
}

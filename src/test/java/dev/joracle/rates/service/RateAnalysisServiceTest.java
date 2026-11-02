package dev.joracle.rates.service;

import dev.joracle.rates.model.MarketSummary;
import dev.joracle.rates.model.P2POffer;
import dev.joracle.rates.model.TradeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RateAnalysisServiceTest {

    private RateAnalysisService analysisService;

    @BeforeEach
    void setUp() {
        analysisService = new RateAnalysisService();
    }

    @Test
    @DisplayName("Should handle empty offers list gracefully")
    void shouldHandleEmptyOffers() {
        MarketSummary summary = analysisService.analyzeMarket(List.of(), TradeType.BUY, "BOB", "USDT");

        assertNotNull(summary);
        assertEquals(0, summary.sampleCount());
        assertEquals(0, summary.totalReceived());
        assertEquals(0, summary.validCount());
        assertEquals(0, summary.discardedCount());
        assertEquals(BigDecimal.ZERO, summary.referencePrice());
        assertEquals(BigDecimal.ZERO, summary.averagePrice());
        assertEquals(BigDecimal.ZERO, summary.medianPrice());
        assertTrue(summary.topOffers().isEmpty());
        assertTrue(summary.bankSummaries().isEmpty());
    }

    @Test
    @DisplayName("Should calculate correct average, median, min, and max for BUY offers")
    void shouldCalculateMetricsCorrectlyForBuy() {
        List<P2POffer> offers = List.of(
                new P2POffer("1", TradeType.BUY, "SellerA", new BigDecimal("12.00"), BigDecimal.ZERO, BigDecimal.ZERO, 10, 1.0, List.of("Banco Union")),
                new P2POffer("2", TradeType.BUY, "SellerB", new BigDecimal("12.10"), BigDecimal.ZERO, BigDecimal.ZERO, 20, 1.0, List.of("Banco Union", "Banco de Credito")),
                new P2POffer("3", TradeType.BUY, "SellerC", new BigDecimal("12.50"), BigDecimal.ZERO, BigDecimal.ZERO, 30, 0.95, List.of("Banco BNB"))
        );

        MarketSummary summary = analysisService.analyzeMarket(offers, TradeType.BUY, "BOB", "USDT");

        assertNotNull(summary);
        assertEquals(3, summary.totalReceived());
        assertEquals(3, summary.validCount());
        assertEquals(0, summary.discardedCount());
        assertEquals(3, summary.sampleCount());

        // Min = 12.00, Max = 12.50
        assertEquals(new BigDecimal("12.00"), summary.minPrice());
        assertEquals(new BigDecimal("12.50"), summary.maxPrice());
        // Median / Reference = 12.10
        assertEquals(new BigDecimal("12.10"), summary.medianPrice());
        assertEquals(new BigDecimal("12.10"), summary.referencePrice());
        // Sum = 36.60, Avg = 12.20
        assertEquals(new BigDecimal("12.20"), summary.averagePrice());

        // For BUY, offers should be sorted ascending by price (best price for buyer first)
        assertEquals("SellerA", summary.topOffers().get(0).advertiserNick());
        assertEquals("SellerB", summary.topOffers().get(1).advertiserNick());
        assertEquals("SellerC", summary.topOffers().get(2).advertiserNick());

        // Bank distribution check
        assertFalse(summary.bankSummaries().isEmpty());
        assertEquals("Banco Unión", summary.bankSummaries().get(0).bankName());
        assertEquals(2, summary.bankSummaries().get(0).offerCount());
    }

    @Test
    @DisplayName("Should sort SELL offers descending (highest price first for sellers)")
    void shouldSortSellOffersDescending() {
        List<P2POffer> offers = List.of(
                new P2POffer("1", TradeType.SELL, "BuyerLow", new BigDecimal("11.80"), BigDecimal.ZERO, BigDecimal.ZERO, 10, 1.0, List.of("Banco Union")),
                new P2POffer("2", TradeType.SELL, "BuyerHigh", new BigDecimal("11.95"), BigDecimal.ZERO, BigDecimal.ZERO, 20, 1.0, List.of("Banco BNB")),
                new P2POffer("3", TradeType.SELL, "BuyerMid", new BigDecimal("11.90"), BigDecimal.ZERO, BigDecimal.ZERO, 30, 0.99, List.of("Banco Union"))
        );

        MarketSummary summary = analysisService.analyzeMarket(offers, TradeType.SELL, "BOB", "USDT");

        assertNotNull(summary);
        assertEquals(3, summary.validCount());
        // Top offer for SELL should be BuyerHigh (11.95)
        assertEquals("BuyerHigh", summary.topOffers().get(0).advertiserNick());
        assertEquals("BuyerMid", summary.topOffers().get(1).advertiserNick());
        assertEquals("BuyerLow", summary.topOffers().get(2).advertiserNick());
    }

    @Test
    @DisplayName("Should calculate median for even number of items")
    void shouldCalculateMedianForEvenCount() {
        List<P2POffer> offers = List.of(
                new P2POffer("1", TradeType.BUY, "A", new BigDecimal("12.00"), BigDecimal.ZERO, BigDecimal.ZERO, 1, 1.0, List.of("BNB")),
                new P2POffer("2", TradeType.BUY, "B", new BigDecimal("12.20"), BigDecimal.ZERO, BigDecimal.ZERO, 1, 1.0, List.of("BNB")),
                new P2POffer("3", TradeType.BUY, "C", new BigDecimal("12.40"), BigDecimal.ZERO, BigDecimal.ZERO, 1, 1.0, List.of("BNB")),
                new P2POffer("4", TradeType.BUY, "D", new BigDecimal("12.60"), BigDecimal.ZERO, BigDecimal.ZERO, 1, 1.0, List.of("BNB"))
        );

        MarketSummary summary = analysisService.analyzeMarket(offers, TradeType.BUY, "BOB", "USDT");

        // Median of (12.20 + 12.40)/2 = 12.30
        assertEquals(new BigDecimal("12.30"), summary.medianPrice());
        assertEquals(new BigDecimal("12.30"), summary.referencePrice());
    }

    @Test
    @DisplayName("Should filter out duplicates by advNo and invalid prices or limits")
    void shouldFilterDuplicatesAndInvalidOffers() {
        List<P2POffer> offers = List.of(
                // Valid offer
                new P2POffer("100", TradeType.BUY, "ValidSeller", new BigDecimal("12.00"), new BigDecimal("100"), new BigDecimal("5000"), 10, 1.0, List.of("BNB")),
                // Duplicate of advNo 100
                new P2POffer("100", TradeType.BUY, "DuplicateSeller", new BigDecimal("12.00"), new BigDecimal("100"), new BigDecimal("5000"), 10, 1.0, List.of("BNB")),
                // Invalid negative or zero price
                new P2POffer("101", TradeType.BUY, "BadPrice", BigDecimal.ZERO, new BigDecimal("100"), new BigDecimal("5000"), 10, 1.0, List.of("BNB")),
                // Inconsistent limits (min > max)
                new P2POffer("102", TradeType.BUY, "BadLimits", new BigDecimal("12.10"), new BigDecimal("10000"), new BigDecimal("500"), 10, 1.0, List.of("BNB")),
                // Second valid offer
                new P2POffer("103", TradeType.BUY, "SecondValid", new BigDecimal("12.04"), new BigDecimal("50"), new BigDecimal("1000"), 10, 1.0, List.of("Banco Union"))
        );

        MarketSummary summary = analysisService.analyzeMarket(offers, TradeType.BUY, "BOB", "USDT");

        assertEquals(5, summary.totalReceived());
        assertEquals(2, summary.validCount());
        assertEquals(3, summary.discardedCount());
        assertEquals(2, summary.topOffers().size());
        assertEquals("ValidSeller", summary.topOffers().get(0).advertiserNick());
        assertEquals("SecondValid", summary.topOffers().get(1).advertiserNick());
    }
}

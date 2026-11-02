package dev.joracle.rates.repository;

import dev.joracle.rates.model.MarketSummary;
import dev.joracle.rates.model.RateSnapshot;
import dev.joracle.rates.model.TradeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryRateRepositoryTest {

    private MarketSummary createDummyMarket(TradeType type, BigDecimal price) {
        return new MarketSummary(type, "BOB", "USDT", price, price, price, price, 1, List.of(), List.of());
    }

    @Test
    @DisplayName("Should return empty when no snapshot has been saved")
    void shouldReturnEmptyInitially() {
        InMemoryRateRepository repo = new InMemoryRateRepository(10);
        assertTrue(repo.findLatest().isEmpty());
        assertTrue(repo.findRecent(5).isEmpty());
    }

    @Test
    @DisplayName("Should store and return the latest snapshot")
    void shouldStoreAndRetrieveLatest() {
        InMemoryRateRepository repo = new InMemoryRateRepository(10);

        RateSnapshot snapshot1 = new RateSnapshot(
                Instant.now().minusSeconds(60),
                createDummyMarket(TradeType.BUY, new BigDecimal("12.00")),
                createDummyMarket(TradeType.SELL, new BigDecimal("11.80"))
        );

        RateSnapshot snapshot2 = new RateSnapshot(
                Instant.now(),
                createDummyMarket(TradeType.BUY, new BigDecimal("12.05")),
                createDummyMarket(TradeType.SELL, new BigDecimal("11.85"))
        );

        repo.save(snapshot1);
        repo.save(snapshot2);

        Optional<RateSnapshot> latest = repo.findLatest();
        assertTrue(latest.isPresent());
        assertEquals(snapshot2, latest.get());

        List<RateSnapshot> recent = repo.findRecent(10);
        assertEquals(2, recent.size());
        assertEquals(snapshot2, recent.get(0)); // latest first
        assertEquals(snapshot1, recent.get(1));
    }

    @Test
    @DisplayName("Should evict oldest entries when capacity is exceeded")
    void shouldEvictOldestEntries() {
        InMemoryRateRepository repo = new InMemoryRateRepository(2);

        RateSnapshot s1 = new RateSnapshot(Instant.now(), createDummyMarket(TradeType.BUY, BigDecimal.ONE), createDummyMarket(TradeType.SELL, BigDecimal.ONE));
        RateSnapshot s2 = new RateSnapshot(Instant.now(), createDummyMarket(TradeType.BUY, BigDecimal.TEN), createDummyMarket(TradeType.SELL, BigDecimal.TEN));
        RateSnapshot s3 = new RateSnapshot(Instant.now(), createDummyMarket(TradeType.BUY, new BigDecimal("100")), createDummyMarket(TradeType.SELL, new BigDecimal("100")));

        repo.save(s1);
        repo.save(s2);
        repo.save(s3);

        List<RateSnapshot> recent = repo.findRecent(10);
        assertEquals(2, recent.size());
        assertEquals(s3, recent.get(0));
        assertEquals(s2, recent.get(1));
    }
}

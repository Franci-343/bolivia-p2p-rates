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
        return new MarketSummary(
                type, "BOB", "USDT",
                price, price, price, price, price,
                1, 1, 0,
                List.of(), List.of()
        );
    }

    private RateSnapshot createSnapshot(long cycleId, Instant timestamp, boolean stale) {
        return new RateSnapshot(
                cycleId,
                timestamp,
                150L,
                stale,
                stale ? "STALE" : "OK",
                createDummyMarket(TradeType.BUY, new BigDecimal("12.00")),
                createDummyMarket(TradeType.SELL, new BigDecimal("11.80"))
        );
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

        RateSnapshot snapshot1 = createSnapshot(1, Instant.now().minusSeconds(60), false);
        RateSnapshot snapshot2 = createSnapshot(2, Instant.now(), false);

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
    @DisplayName("Should mark snapshot as stale when markStale is invoked")
    void shouldMarkSnapshotAsStale() {
        RateSnapshot original = createSnapshot(1, Instant.now(), false);
        assertFalse(original.stale());
        assertEquals("OK", original.status());

        RateSnapshot stale = original.markStale();
        assertTrue(stale.stale());
        assertEquals("STALE", stale.status());
        assertEquals(original.cycleId(), stale.cycleId());
    }

    @Test
    @DisplayName("Should evict oldest entries when capacity is exceeded")
    void shouldEvictOldestEntries() {
        InMemoryRateRepository repo = new InMemoryRateRepository(2);

        RateSnapshot s1 = createSnapshot(1, Instant.now().minusSeconds(30), false);
        RateSnapshot s2 = createSnapshot(2, Instant.now().minusSeconds(20), false);
        RateSnapshot s3 = createSnapshot(3, Instant.now().minusSeconds(10), false);

        repo.save(s1);
        repo.save(s2);
        repo.save(s3);

        List<RateSnapshot> recent = repo.findRecent(10);
        assertEquals(2, recent.size());
        assertEquals(s3, recent.get(0));
        assertEquals(s2, recent.get(1));
    }
}

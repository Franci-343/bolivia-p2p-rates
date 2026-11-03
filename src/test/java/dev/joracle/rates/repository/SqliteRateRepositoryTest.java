package dev.joracle.rates.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.joracle.rates.config.JsonConfig;
import dev.joracle.rates.db.DatabaseManager;
import dev.joracle.rates.db.DatabaseMigrator;
import dev.joracle.rates.model.BankRateSummary;
import dev.joracle.rates.model.MarketSummary;
import dev.joracle.rates.model.P2POffer;
import dev.joracle.rates.model.RateSnapshot;
import dev.joracle.rates.model.TradeType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class SqliteRateRepositoryTest {

    @TempDir
    Path tempDir;

    private DatabaseManager databaseManager;
    private ObjectMapper objectMapper;
    private SqliteRateRepository repository;

    @BeforeEach
    void setUp() {
        Path dbPath = tempDir.resolve("test_rates.db");
        databaseManager = new DatabaseManager(dbPath.toString());
        objectMapper = JsonConfig.createObjectMapper();

        // Run migrations from project's database/ folder
        DatabaseMigrator migrator = new DatabaseMigrator(databaseManager, Path.of("database"));
        int applied = migrator.migrate();
        assertTrue(applied >= 2, "Expected at least 2 migrations to be applied");

        repository = new SqliteRateRepository(databaseManager, objectMapper);
    }

    @AfterEach
    void tearDown() {
        if (databaseManager != null) {
            databaseManager.close();
        }
    }

    @Test
    void shouldSaveAndRetrieveLatestSnapshot() {
        RateSnapshot snapshot = createSampleSnapshot(1L, "OK", false, new BigDecimal("12.02"), new BigDecimal("11.98"));

        repository.save(snapshot);

        Optional<RateSnapshot> latestOpt = repository.findLatest();
        assertTrue(latestOpt.isPresent(), "Latest snapshot should be present");

        RateSnapshot latest = latestOpt.get();
        assertEquals(1L, latest.cycleId());
        assertEquals("OK", latest.status());
        assertFalse(latest.stale());
        assertEquals(new BigDecimal("12.02"), latest.buyMarket().referencePrice());
        assertEquals(new BigDecimal("11.98"), latest.sellMarket().referencePrice());

        // Verify bank summaries preserved
        assertEquals(1, latest.buyMarket().bankSummaries().size());
        assertEquals("Banco Union", latest.buyMarket().bankSummaries().getFirst().bankName());
    }

    @Test
    void shouldPreserveDataAcrossRepositoryRestarts() {
        RateSnapshot snapshot = createSampleSnapshot(42L, "OK", false, new BigDecimal("12.50"), new BigDecimal("12.40"));
        repository.save(snapshot);

        // Instantiate new repository simulating application restart
        SqliteRateRepository restartedRepo = new SqliteRateRepository(databaseManager, objectMapper);

        Optional<RateSnapshot> latest = restartedRepo.findLatest();
        assertTrue(latest.isPresent());
        assertEquals(42L, latest.get().cycleId());
        assertEquals(new BigDecimal("12.50"), latest.get().buyMarket().referencePrice());
    }

    @Test
    void shouldRetrieveRecentHistoryInDescendingOrder() {
        for (long i = 1; i <= 5; i++) {
            repository.save(createSampleSnapshot(i, "OK", false,
                    new BigDecimal("12.0" + i), new BigDecimal("11.9" + i)));
        }

        List<RateSnapshot> history = repository.findRecent(3);
        assertEquals(3, history.size());
        assertEquals(5L, history.get(0).cycleId());
        assertEquals(4L, history.get(1).cycleId());
        assertEquals(3L, history.get(2).cycleId());
    }

    @Test
    void shouldHandleStaleSnapshot() {
        RateSnapshot fresh = createSampleSnapshot(10L, "OK", false, new BigDecimal("12.00"), new BigDecimal("11.95"));
        repository.save(fresh);

        RateSnapshot stale = fresh.markStale();
        repository.save(stale);

        Optional<RateSnapshot> latest = repository.findLatest();
        assertTrue(latest.isPresent());
        assertTrue(latest.get().stale());
        assertEquals("STALE", latest.get().status());
    }

    private RateSnapshot createSampleSnapshot(long cycleId, String status, boolean stale,
                                              BigDecimal buyRef, BigDecimal sellRef) {
        BankRateSummary bankSummary = new BankRateSummary("Banco Union", buyRef, buyRef, buyRef, 3);
        P2POffer offer = new P2POffer(
                "adv123",
                TradeType.BUY,
                "TraderPro",
                buyRef,
                new BigDecimal("100.00"),
                new BigDecimal("6000.00"),
                50,
                0.99,
                List.of("Banco Union")
        );

        MarketSummary buy = new MarketSummary(
                TradeType.BUY,
                "BOB",
                "USDT",
                buyRef,
                buyRef,
                buyRef,
                buyRef,
                buyRef,
                10,
                10,
                0,
                List.of(bankSummary),
                List.of(offer)
        );

        MarketSummary sell = new MarketSummary(
                TradeType.SELL,
                "BOB",
                "USDT",
                sellRef,
                sellRef,
                sellRef,
                sellRef,
                sellRef,
                10,
                10,
                0,
                List.of(),
                List.of()
        );

        return new RateSnapshot(
                cycleId,
                Instant.now(),
                150L,
                stale,
                status,
                buy,
                sell
        );
    }
}

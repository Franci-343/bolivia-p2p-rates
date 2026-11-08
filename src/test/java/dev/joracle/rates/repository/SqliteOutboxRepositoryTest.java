package dev.joracle.rates.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.joracle.rates.config.JsonConfig;
import dev.joracle.rates.db.DatabaseManager;
import dev.joracle.rates.db.DatabaseMigrator;
import dev.joracle.rates.model.ApiClient;
import dev.joracle.rates.model.MarketSummary;
import dev.joracle.rates.model.OutboxEvent;
import dev.joracle.rates.model.RateSnapshot;
import dev.joracle.rates.model.TradeType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteOutboxRepositoryTest {

    @TempDir
    Path tempDir;

    private DatabaseManager databaseManager;
    private ObjectMapper objectMapper;
    private SqliteOutboxRepository outboxRepo;
    private SqliteRateRepository rateRepo;
    private SqliteApiClientRepository clientRepo;

    @BeforeEach
    void setUp() {
        Path dbPath = tempDir.resolve("test_outbox.db");
        databaseManager = new DatabaseManager(dbPath.toString());
        objectMapper = JsonConfig.createObjectMapper();

        DatabaseMigrator migrator = new DatabaseMigrator(databaseManager, Path.of("database"));
        migrator.migrate();

        outboxRepo = new SqliteOutboxRepository(databaseManager);
        rateRepo = new SqliteRateRepository(databaseManager, objectMapper, outboxRepo);
        clientRepo = new SqliteApiClientRepository(databaseManager, outboxRepo, objectMapper);
    }

    @AfterEach
    void tearDown() {
        if (databaseManager != null) {
            databaseManager.close();
        }
    }

    @Test
    void shouldEnqueueAndRetrievePendingEventsInSequentialOrder() {
        String instanceId = outboxRepo.getSourceInstanceId();
        assertNotNull(instanceId);
        assertTrue(instanceId.startsWith("inst_"));

        RateSnapshot snapshot1 = createSampleSnapshot(101L);
        RateSnapshot snapshot2 = createSampleSnapshot(102L);

        rateRepo.save(snapshot1);
        rateRepo.save(snapshot2);

        List<OutboxEvent> pending = outboxRepo.findPendingEvents(10);
        // Note: Initial migration backfilled 2 seed clients from 002
        // So pending events will have seed clients + 2 new snapshots
        assertTrue(pending.size() >= 2);

        // Verify ordering by seq_id
        for (int i = 0; i < pending.size() - 1; i++) {
            assertTrue(pending.get(i).seqId() < pending.get(i + 1).seqId(),
                    "Events must be strictly ordered by seq_id ASC");
        }
    }

    @Test
    void shouldRetryFailedEventsAndNotLockThem() {
        RateSnapshot snapshot = createSampleSnapshot(201L);
        rateRepo.save(snapshot);

        List<OutboxEvent> pending = outboxRepo.findPendingEvents(50);
        OutboxEvent target = pending.stream()
                .filter(e -> e.aggregateId().equals("201"))
                .findFirst()
                .orElseThrow();

        outboxRepo.markBatchFailed(List.of(target.seqId()), "Connection timeout to Oracle");

        // Query again: the failed event MUST still be returned for retry
        List<OutboxEvent> retryable = outboxRepo.findPendingEvents(50);
        OutboxEvent failedEvent = retryable.stream()
                .filter(e -> e.seqId() == target.seqId())
                .findFirst()
                .orElseThrow();

        assertEquals(OutboxEvent.STATUS_FAILED, failedEvent.status());
        assertEquals(1, failedEvent.retryCount());
        assertTrue(failedEvent.lastError().contains("Connection timeout"));
    }

    @Test
    void shouldMarkConfirmedAndPurgeConfirmedOutboxEvents() throws Exception {
        RateSnapshot snapshot = createSampleSnapshot(301L);
        rateRepo.save(snapshot);

        List<OutboxEvent> pending = outboxRepo.findPendingEvents(50);
        OutboxEvent snapEvent = pending.stream()
                .filter(e -> e.aggregateId().equals("301"))
                .findFirst()
                .orElseThrow();

        outboxRepo.markBatchConfirmed(List.of(snapEvent.seqId()), List.of("301"));

        // Verify outbox event is purged
        List<OutboxEvent> afterConfirm = outboxRepo.findPendingEvents(50);
        assertFalse(afterConfirm.stream().anyMatch(e -> e.seqId() == snapEvent.seqId()),
                "Confirmed outbox event should be purged from sync_outbox");

        // Verify snapshot in rate_snapshots has synced_at timestamp
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement("SELECT synced_at FROM rate_snapshots WHERE cycle_id = ?")) {
            pstmt.setLong(1, 301L);
            try (ResultSet rs = pstmt.executeQuery()) {
                assertTrue(rs.next());
                assertNotNull(rs.getString("synced_at"));
            }
        }
    }

    @Test
    void shouldPurgeOldSyncedSnapshotsWhileKeepingLast100AndAllUnsynced() throws Exception {
        // Create 120 snapshots
        for (long i = 1; i <= 120; i++) {
            rateRepo.save(createSampleSnapshot(i));
        }

        // Mark first 100 as synced
        try (Connection conn = databaseManager.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("UPDATE rate_snapshots SET synced_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now') WHERE cycle_id <= 100");
        }

        // Snapshots 101 to 120 remain unsynced (synced_at IS NULL)
        // Purge keeping the last 100 records
        int purged = outboxRepo.purgeOldSyncedSnapshots(100);

        // Snapshots eligible for purge:
        // Total snapshots: 120 (id 1..120)
        // Kept by LIMIT 100: id 21..120
        // Older than last 100: id 1..20
        // All id 1..20 have synced_at != null, so exactly 20 should be purged!
        assertEquals(20, purged, "Should purge exactly 20 old synced records");

        // Verify total count remaining is 100
        try (Connection conn = databaseManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM rate_snapshots")) {
            assertTrue(rs.next());
            assertEquals(100, rs.getInt(1));
        }

        // Now test unsynced protection:
        // Even if old, if synced_at IS NULL it must NEVER be deleted
        try (Connection conn = databaseManager.getConnection();
             Statement stmt = conn.createStatement()) {
            // Set synced_at to NULL for all remaining snapshots
            stmt.execute("UPDATE rate_snapshots SET synced_at = NULL");
        }
        int purgedUnsynced = outboxRepo.purgeOldSyncedSnapshots(50);
        assertEquals(0, purgedUnsynced, "Unsynced snapshots must NEVER be purged");
    }

    @Test
    void shouldGenerateUniqueEventsForClientUpsertAndClientDelete() {
        // 1. Create client
        ApiClient client = clientRepo.save("test_bot", "Test Bot", "hash123", "CONSUMER", 60);
        assertNotNull(client);

        List<OutboxEvent> events = outboxRepo.findPendingEvents(50);
        OutboxEvent upsertEvent = events.stream()
                .filter(e -> e.aggregateId().equals("test_bot") && e.eventType().equals(OutboxEvent.TYPE_API_CLIENT_UPSERT))
                .findFirst()
                .orElseThrow();
        assertTrue(upsertEvent.eventId().contains("API_CLIENT_UPSERT:test_bot"));

        // 2. Toggle active
        boolean toggled = clientRepo.toggleActive(client.id());
        assertTrue(toggled);

        events = outboxRepo.findPendingEvents(50);
        long upsertCount = events.stream()
                .filter(e -> e.aggregateId().equals("test_bot") && e.eventType().equals(OutboxEvent.TYPE_API_CLIENT_UPSERT))
                .count();
        assertEquals(2, upsertCount, "Toggling active should create a second distinct UPSERT event");

        // 3. Delete client
        boolean deleted = clientRepo.delete(client.id());
        assertTrue(deleted);

        events = outboxRepo.findPendingEvents(50);
        OutboxEvent deleteEvent = events.stream()
                .filter(e -> e.aggregateId().equals("test_bot") && e.eventType().equals(OutboxEvent.TYPE_API_CLIENT_DELETE))
                .findFirst()
                .orElseThrow();
        assertTrue(deleteEvent.eventId().contains("API_CLIENT_DELETE:test_bot"));
    }

    private RateSnapshot createSampleSnapshot(long cycleId) {
        BigDecimal price = new BigDecimal("12.00");
        MarketSummary buy = new MarketSummary(TradeType.BUY, "BOB", "USDT", price, price, price, price, price, 1, 1, 0, List.of(), List.of());
        MarketSummary sell = new MarketSummary(TradeType.SELL, "BOB", "USDT", price, price, price, price, price, 1, 1, 0, List.of(), List.of());
        return new RateSnapshot(cycleId, Instant.now(), 100L, false, "OK", buy, sell);
    }
}

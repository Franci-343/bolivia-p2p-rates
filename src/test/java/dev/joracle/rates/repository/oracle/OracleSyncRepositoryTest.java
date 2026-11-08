package dev.joracle.rates.repository.oracle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.joracle.rates.config.AppConfig;
import dev.joracle.rates.config.JsonConfig;
import dev.joracle.rates.db.OracleDatabaseManager;
import dev.joracle.rates.model.OutboxEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OracleSyncRepositoryTest {

    @TempDir
    Path tempDir;

    private HikariDataSource dataSource;
    private OracleDatabaseManager dbManager;
    private ObjectMapper objectMapper;
    private OracleSyncRepository syncRepo;

    @BeforeEach
    void setUp() throws Exception {
        Path dbPath = tempDir.resolve("mock_oracle.db");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + dbPath.toString().replace('\\', '/'));
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(0);
        this.dataSource = new HikariDataSource(config);

        AppConfig appConfig = new AppConfig(
                8080, 600, 60, 10, "BOB", "USDT", "database/rates.db", "",
                true, "jdbc:sqlite:" + dbPath.toString().replace('\\', '/'), "BOLIVIA_P2P", "", "", 1, 60, 25, 100
        );
        this.dbManager = new OracleDatabaseManager(appConfig, dataSource);
        this.objectMapper = JsonConfig.createObjectMapper();
        this.syncRepo = new OracleSyncRepository(dbManager, objectMapper);

        // Initialize mock Oracle schema tables in test SQLite DB
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE PROCESSED_EVENTS (
                        EVENT_ID TEXT PRIMARY KEY,
                        EVENT_TYPE TEXT NOT NULL,
                        APPLIED_AT TEXT NOT NULL DEFAULT (CURRENT_TIMESTAMP)
                    );
                    """);

            stmt.execute("""
                    CREATE TABLE RATE_SNAPSHOTS (
                        ID INTEGER PRIMARY KEY AUTOINCREMENT,
                        SOURCE_INSTANCE_ID TEXT NOT NULL,
                        LOCAL_SNAPSHOT_ID INTEGER NOT NULL,
                        CYCLE_ID INTEGER NOT NULL,
                        CAPTURED_AT TEXT NOT NULL,
                        DURATION_MS INTEGER NOT NULL,
                        IS_STALE INTEGER DEFAULT 0 NOT NULL,
                        STATUS TEXT NOT NULL,
                        FIAT TEXT DEFAULT 'BOB' NOT NULL,
                        ASSET TEXT DEFAULT 'USDT' NOT NULL,
                        BUY_REFERENCE_PRICE NUMERIC NOT NULL,
                        BUY_AVERAGE_PRICE NUMERIC NOT NULL,
                        BUY_MEDIAN_PRICE NUMERIC NOT NULL,
                        BUY_MIN_PRICE NUMERIC NOT NULL,
                        BUY_MAX_PRICE NUMERIC NOT NULL,
                        BUY_VALID_COUNT INTEGER NOT NULL,
                        BUY_DISCARDED_COUNT INTEGER NOT NULL,
                        BUY_TOTAL_RECEIVED INTEGER NOT NULL,
                        BUY_DETAILS_JSON TEXT,
                        SELL_REFERENCE_PRICE NUMERIC NOT NULL,
                        SELL_AVERAGE_PRICE NUMERIC NOT NULL,
                        SELL_MEDIAN_PRICE NUMERIC NOT NULL,
                        SELL_MIN_PRICE NUMERIC NOT NULL,
                        SELL_MAX_PRICE NUMERIC NOT NULL,
                        SELL_VALID_COUNT INTEGER NOT NULL,
                        SELL_DISCARDED_COUNT INTEGER NOT NULL,
                        SELL_TOTAL_RECEIVED INTEGER NOT NULL,
                        SELL_DETAILS_JSON TEXT,
                        SYNCED_AT TEXT NOT NULL DEFAULT (CURRENT_TIMESTAMP),
                        CONSTRAINT UK_SNAPSHOT_SOURCE_LOCAL UNIQUE (SOURCE_INSTANCE_ID, LOCAL_SNAPSHOT_ID)
                    );
                    """);

            stmt.execute("""
                    CREATE TABLE API_CLIENTS (
                        ID INTEGER PRIMARY KEY AUTOINCREMENT,
                        CLIENT_ID TEXT UNIQUE NOT NULL,
                        CLIENT_NAME TEXT NOT NULL,
                        API_KEY_HASH TEXT NOT NULL,
                        ROLE TEXT DEFAULT 'CONSUMER' NOT NULL,
                        RATE_LIMIT_RPM INTEGER DEFAULT 60 NOT NULL,
                        IS_ACTIVE INTEGER DEFAULT 1 NOT NULL,
                        CREATED_AT TEXT NOT NULL DEFAULT (CURRENT_TIMESTAMP),
                        UPDATED_AT TEXT NOT NULL DEFAULT (CURRENT_TIMESTAMP),
                        DELETED_AT TEXT DEFAULT NULL
                    );
                    """);
        }
    }

    @AfterEach
    void tearDown() {
        if (dbManager != null) {
            dbManager.close();
        }
    }

    @Test
    void shouldSyncSnapshotAndRecordProcessedEvent() throws Exception {
        String payloadJson = """
                {
                    "local_id": 1,
                    "cycle_id": 1001,
                    "captured_at": "2026-11-08T18:00:00Z",
                    "duration_ms": 120,
                    "is_stale": 0,
                    "status": "OK",
                    "fiat": "BOB",
                    "asset": "USDT",
                    "buy_reference_price": "12.05",
                    "buy_average_price": "12.05",
                    "buy_median_price": "12.05",
                    "buy_min_price": "12.00",
                    "buy_max_price": "12.10",
                    "buy_valid_count": 10,
                    "buy_discarded_count": 0,
                    "buy_total_received": 10,
                    "buy_details_json": "{}",
                    "sell_reference_price": "11.95",
                    "sell_average_price": "11.95",
                    "sell_median_price": "11.95",
                    "sell_min_price": "11.90",
                    "sell_max_price": "12.00",
                    "sell_valid_count": 10,
                    "sell_discarded_count": 0,
                    "sell_total_received": 10,
                    "sell_details_json": "{}"
                }
                """;

        OutboxEvent event = new OutboxEvent(
                1L, "inst_test:RATE_SNAPSHOT:1001:1", OutboxEvent.TYPE_RATE_SNAPSHOT,
                "1001", payloadJson, "PENDING", 0, null, "2026-11-08T18:00:00Z", null
        );

        OracleSyncRepository.SyncBatchResult result = syncRepo.applyBatch(List.of(event));

        assertEquals(1, result.appliedCount());
        assertEquals(List.of(1L), result.confirmedSeqIds());
        assertEquals(List.of("1001"), result.confirmedSnapshotCycleIds());

        // Verify inserted into RATE_SNAPSHOTS
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT cycle_id, buy_reference_price FROM RATE_SNAPSHOTS WHERE cycle_id = 1001")) {
            assertTrue(rs.next());
            assertEquals(1001, rs.getLong("cycle_id"));
            assertEquals("12.05", rs.getString("buy_reference_price"));
        }

        // Verify recorded into PROCESSED_EVENTS
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT event_id FROM PROCESSED_EVENTS WHERE event_id = 'inst_test:RATE_SNAPSHOT:1001:1'")) {
            assertTrue(rs.next());
        }
    }

    @Test
    void shouldHandleDuplicateEventsIdempotentlyWithoutFailure() throws Exception {
        String payloadJson = "{\"local_id\": 2, \"cycle_id\": 1002, \"captured_at\": \"2026-11-08T18:00:00Z\"}";
        OutboxEvent event = new OutboxEvent(
                2L, "inst_test:RATE_SNAPSHOT:1002:2", OutboxEvent.TYPE_RATE_SNAPSHOT,
                "1002", payloadJson, "PENDING", 0, null, "2026-11-08T18:00:00Z", null
        );

        // First apply
        OracleSyncRepository.SyncBatchResult res1 = syncRepo.applyBatch(List.of(event));
        assertEquals(1, res1.appliedCount());

        // Second apply (simulating retry after crash before local ACK)
        OracleSyncRepository.SyncBatchResult res2 = syncRepo.applyBatch(List.of(event));
        // Idempotency: must succeed, returning confirmed seqId, without duplicate insertion error
        assertEquals(1, res2.appliedCount());
        assertEquals(List.of(2L), res2.confirmedSeqIds());

        // Verify only 1 snapshot exists in DB
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM RATE_SNAPSHOTS WHERE cycle_id = 1002")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1), "Snapshot must not be duplicated");
        }
    }

    @Test
    void shouldSyncClientUpsertAndClientDelete() throws Exception {
        // 1. Client Upsert (Insert)
        String clientPayload = """
                {
                    "client_id": "telegram_bot",
                    "client_name": "Telegram Rate Bot",
                    "api_key_hash": "abc123hash",
                    "role": "CONSUMER",
                    "rate_limit_rpm": 120,
                    "is_active": 1
                }
                """;

        OutboxEvent upsertEvent = new OutboxEvent(
                3L, "inst_test:API_CLIENT_UPSERT:telegram_bot:op1", OutboxEvent.TYPE_API_CLIENT_UPSERT,
                "telegram_bot", clientPayload, "PENDING", 0, null, "2026-11-08T18:00:00Z", null
        );

        syncRepo.applyBatch(List.of(upsertEvent));

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT client_name, is_active FROM API_CLIENTS WHERE client_id = 'telegram_bot'")) {
            assertTrue(rs.next());
            assertEquals("Telegram Rate Bot", rs.getString("client_name"));
            assertEquals(1, rs.getInt("is_active"));
        }

        // 2. Client Upsert (Update status to inactive)
        String updatePayload = """
                {
                    "client_id": "telegram_bot",
                    "client_name": "Telegram Rate Bot (Disabled)",
                    "api_key_hash": "abc123hash",
                    "role": "CONSUMER",
                    "rate_limit_rpm": 120,
                    "is_active": 0
                }
                """;
        OutboxEvent updateEvent = new OutboxEvent(
                4L, "inst_test:API_CLIENT_UPSERT:telegram_bot:op2", OutboxEvent.TYPE_API_CLIENT_UPSERT,
                "telegram_bot", updatePayload, "PENDING", 0, null, "2026-11-08T18:01:00Z", null
        );
        syncRepo.applyBatch(List.of(updateEvent));

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT client_name, is_active FROM API_CLIENTS WHERE client_id = 'telegram_bot'")) {
            assertTrue(rs.next());
            assertEquals("Telegram Rate Bot (Disabled)", rs.getString("client_name"));
            assertEquals(0, rs.getInt("is_active"));
        }

        // 3. Client Delete (Soft delete)
        String deletePayload = "{\"client_id\": \"telegram_bot\"}";
        OutboxEvent deleteEvent = new OutboxEvent(
                5L, "inst_test:API_CLIENT_DELETE:telegram_bot:op3", OutboxEvent.TYPE_API_CLIENT_DELETE,
                "telegram_bot", deletePayload, "PENDING", 0, null, "2026-11-08T18:02:00Z", null
        );
        syncRepo.applyBatch(List.of(deleteEvent));

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT is_active, deleted_at FROM API_CLIENTS WHERE client_id = 'telegram_bot'")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt("is_active"));
            assertNotNull(rs.getString("deleted_at"));
        }
    }
}

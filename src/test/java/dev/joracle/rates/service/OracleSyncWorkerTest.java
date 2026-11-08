package dev.joracle.rates.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.joracle.rates.config.AppConfig;
import dev.joracle.rates.config.JsonConfig;
import dev.joracle.rates.db.DatabaseManager;
import dev.joracle.rates.db.DatabaseMigrator;
import dev.joracle.rates.db.OracleDatabaseManager;
import dev.joracle.rates.model.MarketSummary;
import dev.joracle.rates.model.RateSnapshot;
import dev.joracle.rates.model.TradeType;
import dev.joracle.rates.repository.SqliteApiClientRepository;
import dev.joracle.rates.repository.SqliteOutboxRepository;
import dev.joracle.rates.repository.SqliteRateRepository;
import dev.joracle.rates.repository.oracle.OracleSyncRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OracleSyncWorkerTest {

    @TempDir
    Path tempDir;

    private DatabaseManager sqliteDbManager;
    private HikariDataSource oracleDataSource;
    private OracleDatabaseManager oracleDbManager;
    private SqliteOutboxRepository outboxRepo;
    private SqliteRateRepository rateRepo;
    private SqliteApiClientRepository clientRepo;
    private OracleSyncRepository oracleSyncRepo;
    private OracleSyncWorker worker;
    private AppConfig appConfig;

    @BeforeEach
    void setUp() throws Exception {
        // 1. SQLite Local setup
        Path sqlitePath = tempDir.resolve("local_rates.db");
        sqliteDbManager = new DatabaseManager(sqlitePath.toString());
        DatabaseMigrator migrator = new DatabaseMigrator(sqliteDbManager, Path.of("database"));
        migrator.migrate();

        outboxRepo = new SqliteOutboxRepository(sqliteDbManager);
        ObjectMapper objectMapper = JsonConfig.createObjectMapper();
        rateRepo = new SqliteRateRepository(sqliteDbManager, objectMapper, outboxRepo);
        clientRepo = new SqliteApiClientRepository(sqliteDbManager, outboxRepo, objectMapper);

        // 2. Mock Oracle DB setup
        Path oraclePath = tempDir.resolve("remote_oracle.db");
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl("jdbc:sqlite:" + oraclePath.toString().replace('\\', '/'));
        hikariConfig.setMaximumPoolSize(1);
        hikariConfig.setMinimumIdle(0);
        oracleDataSource = new HikariDataSource(hikariConfig);

        // Init Oracle mock schema
        try (Connection conn = oracleDataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE PROCESSED_EVENTS (EVENT_ID TEXT PRIMARY KEY, EVENT_TYPE TEXT, APPLIED_AT TEXT DEFAULT (CURRENT_TIMESTAMP));");
            stmt.execute("""
                    CREATE TABLE RATE_SNAPSHOTS (
                        ID INTEGER PRIMARY KEY AUTOINCREMENT,
                        SOURCE_INSTANCE_ID TEXT, LOCAL_SNAPSHOT_ID INTEGER, CYCLE_ID INTEGER,
                        CAPTURED_AT TEXT, DURATION_MS INTEGER, IS_STALE INTEGER, STATUS TEXT,
                        FIAT TEXT, ASSET TEXT, BUY_REFERENCE_PRICE NUMERIC, BUY_AVERAGE_PRICE NUMERIC,
                        BUY_MEDIAN_PRICE NUMERIC, BUY_MIN_PRICE NUMERIC, BUY_MAX_PRICE NUMERIC,
                        BUY_VALID_COUNT INTEGER, BUY_DISCARDED_COUNT INTEGER, BUY_TOTAL_RECEIVED INTEGER,
                        BUY_DETAILS_JSON TEXT, SELL_REFERENCE_PRICE NUMERIC, SELL_AVERAGE_PRICE NUMERIC,
                        SELL_MEDIAN_PRICE NUMERIC, SELL_MIN_PRICE NUMERIC, SELL_MAX_PRICE NUMERIC,
                        SELL_VALID_COUNT INTEGER, SELL_DISCARDED_COUNT INTEGER, SELL_TOTAL_RECEIVED INTEGER,
                        SELL_DETAILS_JSON TEXT, SYNCED_AT TEXT DEFAULT (CURRENT_TIMESTAMP),
                        CONSTRAINT UK_SNAP UNIQUE (SOURCE_INSTANCE_ID, LOCAL_SNAPSHOT_ID)
                    );
                    """);
            stmt.execute("""
                    CREATE TABLE API_CLIENTS (
                        ID INTEGER PRIMARY KEY AUTOINCREMENT,
                        CLIENT_ID TEXT UNIQUE, CLIENT_NAME TEXT, API_KEY_HASH TEXT,
                        ROLE TEXT, RATE_LIMIT_RPM INTEGER, IS_ACTIVE INTEGER,
                        CREATED_AT TEXT DEFAULT (CURRENT_TIMESTAMP),
                        UPDATED_AT TEXT DEFAULT (CURRENT_TIMESTAMP),
                        DELETED_AT TEXT DEFAULT NULL
                    );
                    """);
        }

        appConfig = new AppConfig(
                8080, 600, 60, 10, "BOB", "USDT", sqlitePath.toString(), "",
                true, "jdbc:sqlite:" + oraclePath.toString().replace('\\', '/'), "BOLIVIA_P2P", "", "", 1, 60, 50, 100
        );

        oracleDbManager = new OracleDatabaseManager(appConfig, oracleDataSource);
        oracleSyncRepo = new OracleSyncRepository(oracleDbManager, objectMapper);
        worker = new OracleSyncWorker(oracleDbManager, oracleSyncRepo, outboxRepo, appConfig);
    }

    @AfterEach
    void tearDown() {
        if (worker != null) worker.close();
        if (oracleDbManager != null) oracleDbManager.close();
        if (sqliteDbManager != null) sqliteDbManager.close();
    }

    @Test
    void shouldSyncPendingEventsAndPurgeConfirmedFromOutbox() throws Exception {
        // Enqueue 2 snapshots and 1 client
        rateRepo.save(createSampleSnapshot(501L));
        rateRepo.save(createSampleSnapshot(502L));
        clientRepo.save("bot_alpha", "Bot Alpha", "hashalpha", "CONSUMER", 60);

        long pendingBefore = outboxRepo.countPendingEvents();
        assertTrue(pendingBefore >= 3);

        int synced = worker.syncOnce();
        assertTrue(synced >= 3);
        assertNotNull(worker.getLastSuccessInstant());
        assertNull(worker.getLastError());
        assertEquals(0, worker.getConsecutiveFailures());

        // Verify outbox has 0 pending
        assertEquals(0, outboxRepo.countPendingEvents());

        // Verify Oracle mock DB has the snapshots
        try (Connection conn = oracleDataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM RATE_SNAPSHOTS WHERE CYCLE_ID IN (501, 502)")) {
            assertTrue(rs.next());
            assertEquals(2, rs.getInt(1));
        }

        // Verify Oracle mock DB has the client
        try (Connection conn = oracleDataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT client_name FROM API_CLIENTS WHERE client_id = 'bot_alpha'")) {
            assertTrue(rs.next());
            assertEquals("Bot Alpha", rs.getString(1));
        }
    }

    @Test
    void shouldRemainResilientWhenOracleFailsAndRetryOnRecovery() {
        rateRepo.save(createSampleSnapshot(601L));

        // Simulate Oracle failure by closing datasource
        oracleDataSource.close();

        int result = worker.syncOnce();
        assertEquals(-1, result);
        assertEquals(1, worker.getConsecutiveFailures());
        assertNotNull(worker.getLastError());

        // Verify events in SQLite remain intact
        assertTrue(outboxRepo.countPendingEvents() > 0, "Pending events must NOT be deleted when remote sync fails");
    }

    @Test
    void shouldPreserveAtLeast100SnapshotsDuringCleanup() throws Exception {
        // Insert 105 snapshots
        for (long i = 1; i <= 105; i++) {
            rateRepo.save(createSampleSnapshot(i));
        }

        // Drain sync to Oracle (batch size is 50, so takes multiple syncOnce calls)
        while (outboxRepo.countPendingEvents() > 0) {
            int synced = worker.syncOnce();
            assertTrue(synced > 0);
        }

        // After sync and cleanup: exactly 100 snapshots must remain in SQLite!
        try (Connection conn = sqliteDbManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM rate_snapshots")) {
            assertTrue(rs.next());
            assertEquals(100, rs.getInt(1), "SQLite must retain exactly 100 recent snapshots");
        }
    }

    private RateSnapshot createSampleSnapshot(long cycleId) {
        BigDecimal price = new BigDecimal("12.00");
        MarketSummary buy = new MarketSummary(TradeType.BUY, "BOB", "USDT", price, price, price, price, price, 1, 1, 0, List.of(), List.of());
        MarketSummary sell = new MarketSummary(TradeType.SELL, "BOB", "USDT", price, price, price, price, price, 1, 1, 0, List.of(), List.of());
        return new RateSnapshot(cycleId, Instant.now(), 100L, false, "OK", buy, sell);
    }
}

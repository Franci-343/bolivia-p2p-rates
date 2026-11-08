package dev.joracle.rates;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.joracle.rates.api.RateController;
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
import dev.joracle.rates.service.ApiKeyService;
import dev.joracle.rates.service.OracleSyncWorker;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class OracleOutboxIntegrationTest {

    @TempDir
    Path tempDir;

    private DatabaseManager sqliteDbManager;
    private HikariDataSource oracleDataSource;
    private OracleDatabaseManager oracleDbManager;
    private SqliteOutboxRepository outboxRepo;
    private SqliteRateRepository rateRepo;
    private SqliteApiClientRepository clientRepo;
    private OracleSyncRepository oracleSyncRepo;
    private OracleSyncWorker syncWorker;
    private ApiKeyService apiKeyService;
    private RateController rateController;
    private String adminApiKey;

    @BeforeEach
    void setUp() throws Exception {
        // 1. SQLite Local
        Path sqlitePath = tempDir.resolve("local_integration.db");
        sqliteDbManager = new DatabaseManager(sqlitePath.toString());
        DatabaseMigrator migrator = new DatabaseMigrator(sqliteDbManager, Path.of("database"));
        migrator.migrate();

        ObjectMapper objectMapper = JsonConfig.createObjectMapper();
        outboxRepo = new SqliteOutboxRepository(sqliteDbManager);
        rateRepo = new SqliteRateRepository(sqliteDbManager, objectMapper, outboxRepo);
        clientRepo = new SqliteApiClientRepository(sqliteDbManager, outboxRepo, objectMapper);
        apiKeyService = new ApiKeyService(clientRepo);
        adminApiKey = "test_admin_key_1234567890";
        apiKeyService.ensureAdminKey(adminApiKey);

        // 2. Mock Oracle DB
        Path oraclePath = tempDir.resolve("remote_integration.db");
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl("jdbc:sqlite:" + oraclePath.toString().replace('\\', '/'));
        hikariConfig.setMaximumPoolSize(1);
        hikariConfig.setMinimumIdle(0);
        oracleDataSource = new HikariDataSource(hikariConfig);

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

        AppConfig appConfig = new AppConfig(
                0, 600, 60, 10, "BOB", "USDT", sqlitePath.toString(), "",
                true, "jdbc:sqlite:" + oraclePath.toString().replace('\\', '/'), "BOLIVIA_P2P", "", "", 1, 60, 50, 100
        );

        oracleDbManager = new OracleDatabaseManager(appConfig, oracleDataSource);
        oracleSyncRepo = new OracleSyncRepository(oracleDbManager, objectMapper);
        syncWorker = new OracleSyncWorker(oracleDbManager, oracleSyncRepo, outboxRepo, appConfig);

        // Preload initial snapshot in repository
        rateRepo.save(createSampleSnapshot(1L, "12.05", "11.95"));

        // Setup controller
        rateController = new RateController(rateRepo, null, apiKeyService, syncWorker, outboxRepo);
    }

    @AfterEach
    void tearDown() {
        if (syncWorker != null) syncWorker.close();
        if (oracleDbManager != null) oracleDbManager.close();
        if (sqliteDbManager != null) sqliteDbManager.close();
    }

    @Test
    @DisplayName("Escenario 1: Aislamiento absoluto de HTTP - 100 peticiones concurrentes nunca tocan Oracle")
    void scenario1_httpConcurrencyNeverTouchesOracle() throws Exception {
        int initialOracleConnections = oracleDbManager.getActiveConnections();
        assertEquals(0, initialOracleConnections);

        int requestCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(16);
        List<Callable<Integer>> tasks = new ArrayList<>();

        for (int i = 0; i < requestCount; i++) {
            final int index = i;
            tasks.add(() -> {
                MockContext ctx = new MockContext();
                ctx.headers.put("X-API-Key", adminApiKey);

                invokeMethod(rateController, "authenticateRateRequest", ctx.createContextProxy());
                if (ctx.status == null) {
                    if (index % 3 == 0) {
                        invokeMethod(rateController, "getLatestRates", ctx.createContextProxy());
                    } else if (index % 3 == 1) {
                        ctx.queryParams.put("limit", "10");
                        invokeMethod(rateController, "getRecentHistory", ctx.createContextProxy());
                    } else {
                        invokeMethod(rateController, "getStatus", ctx.createContextProxy());
                    }
                }

                int code = ctx.status != null ? ctx.status.getCode() : 200;
                assertNotNull(ctx.jsonBody, "Response JSON body should not be null");
                return code;
            });
        }

        List<Future<Integer>> futures = executor.invokeAll(tasks);
        for (Future<Integer> f : futures) {
            assertEquals(200, f.get());
        }
        executor.shutdown();

        // Invariant check: zero connections opened towards Oracle by HTTP consumers
        assertEquals(0, oracleDbManager.getActiveConnections());
    }

    @Test
    @DisplayName("Escenario 2 & 3: Caída prolongada de Oracle, acumulación segura, reconexión y catch-up")
    void scenario2and3_extendedOracleOutageAccumulatesAndSyncsUponRecovery() throws Exception {
        // Step A: Disconnect Oracle by closing its datasource
        oracleDataSource.close();

        // Step B: Poller continues saving snapshots in SQLite (e.g. 15 cycles)
        for (long cycle = 10; cycle <= 24; cycle++) {
            rateRepo.save(createSampleSnapshot(cycle, "12.10", "12.00"));
        }

        // Step C: Client operations occur during outage
        clientRepo.save("app_mobile", "App Movil", "hashmobile", "CONSUMER", 100);

        // Verify outbox accumulates events and none are deleted
        long pendingDuringOutage = outboxRepo.countPendingEvents();
        assertTrue(pendingDuringOutage >= 16);

        // Attempt sync during outage: must fail gracefully without deleting or corrupting anything
        int syncResult = syncWorker.syncOnce();
        assertEquals(-1, syncResult);
        assertEquals(1, syncWorker.getConsecutiveFailures());
        assertEquals(pendingDuringOutage, outboxRepo.countPendingEvents());

        // Step D: Oracle recovers! Create fresh connection to mock Oracle
        Path oraclePath = tempDir.resolve("remote_integration.db");
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl("jdbc:sqlite:" + oraclePath.toString().replace('\\', '/'));
        hikariConfig.setMaximumPoolSize(1);
        hikariConfig.setMinimumIdle(0);
        HikariDataSource recoveredDataSource = new HikariDataSource(hikariConfig);

        AppConfig appConfig = new AppConfig(
                0, 600, 60, 10, "BOB", "USDT", "local.db", "",
                true, "jdbc:sqlite:" + oraclePath.toString().replace('\\', '/'), "BOLIVIA_P2P", "", "", 1, 60, 50, 100
        );

        OracleDatabaseManager recoveredDbManager = new OracleDatabaseManager(appConfig, recoveredDataSource);
        OracleSyncRepository recoveredSyncRepo = new OracleSyncRepository(recoveredDbManager, JsonConfig.createObjectMapper());
        OracleSyncWorker recoveredWorker = new OracleSyncWorker(recoveredDbManager, recoveredSyncRepo, outboxRepo, appConfig);

        // Run sync on recovery until pending is 0
        while (outboxRepo.countPendingEvents() > 0) {
            int synced = recoveredWorker.syncOnce();
            assertTrue(synced > 0);
        }

        assertEquals(0, outboxRepo.countPendingEvents(), "All pending events must be synchronized after recovery");
        assertTrue(recoveredWorker.getTotalEventsSynced() >= 16);
        assertEquals(0, recoveredWorker.getConsecutiveFailures());

        // Verify Oracle received all snapshots
        try (Connection conn = recoveredDataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM RATE_SNAPSHOTS WHERE CYCLE_ID >= 10 AND CYCLE_ID <= 24")) {
            assertTrue(rs.next());
            assertEquals(15, rs.getInt(1));
        }

        recoveredWorker.close();
        recoveredDbManager.close();
    }

    @Test
    @DisplayName("Escenario 4: Caída tras COMMIT de Oracle antes de ACK en SQLite - Reintento 100% idempotente")
    void scenario4_crashAfterOracleCommitBeforeLocalAckIsIdempotent() throws Exception {
        RateSnapshot snapshot = createSampleSnapshot(999L, "12.50", "12.40");
        rateRepo.save(snapshot);

        List<dev.joracle.rates.model.OutboxEvent> pending = outboxRepo.findPendingEvents(10);
        dev.joracle.rates.model.OutboxEvent targetEvent = pending.stream()
                .filter(e -> e.aggregateId().equals("999"))
                .findFirst()
                .orElseThrow();

        // 1. First sync to Oracle
        OracleSyncRepository.SyncBatchResult res1 = oracleSyncRepo.applyBatch(List.of(targetEvent));
        assertEquals(1, res1.appliedCount());

        // Simulate crash: SQLite did NOT run outboxRepo.markBatchConfirmed(...)!
        // Event is still in SQLite sync_outbox as PENDING
        assertTrue(outboxRepo.findPendingEvents(10).stream().anyMatch(e -> e.aggregateId().equals("999")));

        // 2. Replay: worker tries to sync the exact same event again
        OracleSyncRepository.SyncBatchResult res2 = oracleSyncRepo.applyBatch(List.of(targetEvent));
        // Idempotency: Remote commit succeeds, PROCESSED_EVENTS prevents duplicate data, confirmedSeqIds returned
        assertEquals(1, res2.appliedCount());
        assertEquals(List.of(targetEvent.seqId()), res2.confirmedSeqIds());

        // Verify only ONE snapshot exists in Oracle RATE_SNAPSHOTS
        try (Connection conn = oracleDataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM RATE_SNAPSHOTS WHERE CYCLE_ID = 999")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1), "Must NOT duplicate snapshot on retry");
        }

        // Now local ACK finishes
        outboxRepo.markBatchConfirmed(res2.confirmedSeqIds(), res2.confirmedSnapshotCycleIds());
        assertFalse(outboxRepo.findPendingEvents(10).stream().anyMatch(e -> e.aggregateId().equals("999")));
    }

    private RateSnapshot createSampleSnapshot(long cycleId, String buyPrice, String sellPrice) {
        BigDecimal buy = new BigDecimal(buyPrice);
        BigDecimal sell = new BigDecimal(sellPrice);
        MarketSummary buyMarket = new MarketSummary(TradeType.BUY, "BOB", "USDT", buy, buy, buy, buy, buy, 5, 5, 0, List.of(), List.of());
        MarketSummary sellMarket = new MarketSummary(TradeType.SELL, "BOB", "USDT", sell, sell, sell, sell, sell, 5, 5, 0, List.of(), List.of());
        return new RateSnapshot(cycleId, Instant.now(), 85L, false, "OK", buyMarket, sellMarket);
    }

    private void invokeMethod(Object target, String methodName, Context ctx) {
        try {
            Method m = target.getClass().getDeclaredMethod(methodName, Context.class);
            m.setAccessible(true);
            m.invoke(target, ctx);
        } catch (Exception e) {
            throw new RuntimeException("Error invoking " + methodName + " on " + target.getClass().getSimpleName(), e);
        }
    }

    private static class MockContext implements InvocationHandler {
        final Map<String, String> headers = new HashMap<>();
        final Map<String, String> queryParams = new HashMap<>();
        final Map<String, Object> attributes = new HashMap<>();
        HttpStatus status;
        Object jsonBody;

        Context createContextProxy() {
            return (Context) Proxy.newProxyInstance(
                    Context.class.getClassLoader(),
                    new Class<?>[]{Context.class},
                    this
            );
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if ("header".equals(name) && args != null && args.length == 1) {
                return headers.get(args[0]);
            }
            if ("queryParam".equals(name) && args != null && args.length == 1) {
                return queryParams.get(args[0]);
            }
            if ("queryParamAsClass".equals(name) && args != null && args.length >= 2) {
                String fieldName = (String) args[0];
                Class<?> targetClass = (Class<?>) args[1];
                String value = queryParams.get(fieldName);
                return new io.javalin.validation.Validation().validator(fieldName, (Class<Object>) targetClass, value);
            }
            if ("attribute".equals(name) && args != null && args.length == 1) {
                return attributes.get(args[0]);
            }
            if ("attribute".equals(name) && args != null && args.length == 2) {
                attributes.put((String) args[0], args[1]);
                return proxy;
            }
            if ("status".equals(name) && args != null && args.length == 1) {
                this.status = (HttpStatus) args[0];
                return proxy;
            }
            if ("status".equals(name) && (args == null || args.length == 0)) {
                return this.status;
            }
            if ("json".equals(name) && args != null && args.length == 1) {
                this.jsonBody = args[0];
                return proxy;
            }
            if ("skipRemainingHandlers".equals(name)) {
                return proxy;
            }
            if ("ip".equals(name)) {
                return "127.0.0.1";
            }
            return null;
        }
    }
}

package dev.joracle.rates.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Application configuration loaded from Java properties files, environment variables,
 * or system properties with sensible defaults.
 * <p>
 * Loading Hierarchy (highest precedence first):
 * 1. JVM System Properties (-Dkey=value)
 * 2. Operating System Environment Variables (KEY=value)
 * 3. External Java Properties file (rates.properties, config.properties, or -Dconfig.file)
 * 4. Internal defaults defined in code
 */
public record AppConfig(
        int serverPort,
        int pollIntervalSeconds,
        int minRefreshCooldownSeconds,
        int binanceRows,
        String fiatCurrency,
        String assetCurrency,
        String databasePath,
        String publicBasePath,
        boolean oracleSyncEnabled,
        String oracleJdbcUrl,
        String oracleDbUser,
        String oracleDbPassword,
        String oracleTnsAdmin,
        int oraclePoolMaxSize,
        int oracleSyncIntervalSeconds,
        int oracleSyncBatchSize,
        int sqliteRecentSnapshots
) {
    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    public AppConfig(
            int serverPort,
            int pollIntervalSeconds,
            int minRefreshCooldownSeconds,
            int binanceRows,
            String fiatCurrency,
            String assetCurrency,
            String databasePath
    ) {
        this(serverPort, pollIntervalSeconds, minRefreshCooldownSeconds, binanceRows,
                fiatCurrency, assetCurrency, databasePath, "",
                false, "jdbc:oracle:thin:@francodb_low", "BOLIVIA_P2P", "", "", 1, 60, 25, 100);
    }

    public AppConfig(
            int serverPort,
            int pollIntervalSeconds,
            int minRefreshCooldownSeconds,
            int binanceRows,
            String fiatCurrency,
            String assetCurrency,
            String databasePath,
            String publicBasePath
    ) {
        this(serverPort, pollIntervalSeconds, minRefreshCooldownSeconds, binanceRows,
                fiatCurrency, assetCurrency, databasePath, publicBasePath,
                false, "jdbc:oracle:thin:@francodb_low", "BOLIVIA_P2P", "", "", 1, 60, 25, 100);
    }

    /**
     * Factory method loading configuration using Java properties file combined with environment variables.
     */
    public static AppConfig fromEnv() {
        return fromProperties(loadProperties());
    }

    /**
     * Factory method building AppConfig from a given Properties object with fallback to environment and defaults.
     */
    public static AppConfig fromProperties(Properties props) {
        int serverPort = getInt(props, "PORT", "server.port", 8080);
        // Default to 600s (10 min) as recommended in PLAN_BOLIVIA_P2P_RATES.md to protect against WAF blocks
        int pollIntervalSeconds = getInt(props, "POLL_INTERVAL_SECONDS", "poll.interval.seconds", 600);
        int cooldownSeconds = getInt(props, "REFRESH_COOLDOWN_SECONDS", "refresh.cooldown.seconds", 60);
        int binanceRows = getInt(props, "BINANCE_FETCH_ROWS", "binance.fetch.rows", 10);
        String fiat = getString(props, "FIAT_CURRENCY", "fiat.currency", "BOB");
        String asset = getString(props, "ASSET_CURRENCY", "asset.currency", "USDT");
        String dbPath = getString(props, "SQLITE_DB_PATH", "sqlite.db.path", "database/rates.db");
        String publicBasePath = normalizeBasePath(getString(props, "PUBLIC_BASE_PATH", "public.base.path", ""));

        // Oracle Cloud Autonomous configuration
        boolean oracleSyncEnabled = getBoolean(props, "ORACLE_SYNC_ENABLED", "oracle.sync.enabled", false);
        String oracleJdbcUrl = getString(props, "ORACLE_JDBC_URL", "oracle.jdbc.url", "jdbc:oracle:thin:@francodb_low");
        String oracleDbUser = getString(props, "ORACLE_DB_USER", "oracle.db.user", "BOLIVIA_P2P");
        String oracleDbPassword = getString(props, "ORACLE_DB_PASSWORD", "oracle.db.password", "");
        String oracleTnsAdmin = getString(props, "ORACLE_TNS_ADMIN", "oracle.tns.admin", "");
        // Strict invariant: pool size is capped at 1 for this process
        int oraclePoolMaxSize = Math.min(1, Math.max(1, getInt(props, "ORACLE_POOL_MAX_SIZE", "oracle.pool.max.size", 1)));
        int oracleSyncIntervalSeconds = Math.max(10, getInt(props, "ORACLE_SYNC_INTERVAL_SECONDS", "oracle.sync.interval.seconds", 60));
        int oracleSyncBatchSize = Math.max(1, Math.min(100, getInt(props, "ORACLE_SYNC_BATCH_SIZE", "oracle.sync.batch.size", 25)));
        int sqliteRecentSnapshots = Math.max(10, getInt(props, "SQLITE_RECENT_SNAPSHOTS", "sqlite.recent.snapshots", 100));

        return new AppConfig(
                serverPort, pollIntervalSeconds, cooldownSeconds, binanceRows,
                fiat, asset, dbPath, publicBasePath,
                oracleSyncEnabled, oracleJdbcUrl, oracleDbUser, oracleDbPassword,
                oracleTnsAdmin, oraclePoolMaxSize, oracleSyncIntervalSeconds,
                oracleSyncBatchSize, sqliteRecentSnapshots
        );
    }

    /**
     * Searches and loads properties from candidate locations:
     * 1. Path specified in system property 'config.file' or env var 'CONFIG_FILE'
     * 2. Working directory: rates.properties
     * 3. Working directory: config.properties
     * 4. Working directory: application.properties
     * 5. User home directory: ~/.config/bolivia-p2p-rates/rates.properties
     * 6. Classpath: application.properties
     */
    public static Properties loadProperties() {
        Properties props = new Properties();

        // 1. Optional classpath resource
        try (InputStream in = AppConfig.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (Exception ignored) {}

        // 2. Candidate external file paths in order of preference
        List<Path> candidates = new ArrayList<>();

        String customFile = System.getProperty("config.file");
        if (customFile == null || customFile.isBlank()) {
            customFile = System.getenv("CONFIG_FILE");
        }
        if (customFile != null && !customFile.isBlank()) {
            candidates.add(Path.of(customFile.trim()));
        }

        candidates.add(Path.of("rates.properties"));
        candidates.add(Path.of("config.properties"));
        candidates.add(Path.of("application.properties"));

        String userHome = System.getProperty("user.home");
        if (userHome != null && !userHome.isBlank()) {
            candidates.add(Path.of(userHome, ".config", "bolivia-p2p-rates", "rates.properties"));
            candidates.add(Path.of(userHome, ".config", "bolivia-p2p-rates", "config.properties"));
        }

        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate) && Files.isReadable(candidate)) {
                try (Reader reader = Files.newBufferedReader(candidate, StandardCharsets.UTF_8)) {
                    props.load(reader);
                    log.info("Loaded application configuration properties from: {}", candidate.toAbsolutePath());
                    break; // Use the first found external properties file
                } catch (Exception e) {
                    log.warn("Failed to read properties file at {}: {}", candidate, e.getMessage());
                }
            }
        }

        return props;
    }

    /**
     * Normalizes a base path ensuring it starts with '/' (if non-empty) and does not end with '/'.
     * For example, "bolivia-p2p-rates", "/bolivia-p2p-rates/" -> "/bolivia-p2p-rates".
     * Empty, whitespace, or "/" returns "".
     */
    public static String normalizeBasePath(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String trimmed = raw.trim();
        if (!trimmed.startsWith("/")) {
            trimmed = "/" + trimmed;
        }
        while (trimmed.endsWith("/") && trimmed.length() > 1) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.equals("/") ? "" : trimmed;
    }

    /**
     * Resolves a public-facing URL by prefixing the configured publicBasePath.
     * Prevents duplicate slashes.
     */
    public String publicUrl(String path) {
        if (path == null || path.isBlank() || path.equals("/")) {
            return publicBasePath.isEmpty() ? "/" : publicBasePath + "/";
        }
        String cleanPath = path.startsWith("/") ? path : "/" + path;
        return publicBasePath.isEmpty() ? cleanPath : publicBasePath + cleanPath;
    }

    private static String getString(Properties props, String envKey, String dottedKey, String defaultValue) {
        // 1. System property (-D)
        String val = System.getProperty(envKey);
        if (val != null && !val.isBlank()) return val.trim();
        if (dottedKey != null) {
            val = System.getProperty(dottedKey);
            if (val != null && !val.isBlank()) return val.trim();
        }

        // 2. Environment variable
        val = System.getenv(envKey);
        if (val != null && !val.isBlank()) return val.trim();
        if (dottedKey != null) {
            String altEnv = dottedKey.replace('.', '_').toUpperCase();
            val = System.getenv(altEnv);
            if (val != null && !val.isBlank()) return val.trim();
        }

        // 3. Properties object (file)
        if (props != null) {
            if (dottedKey != null) {
                val = props.getProperty(dottedKey);
                if (val != null && !val.isBlank()) return val.trim();
            }
            val = props.getProperty(envKey);
            if (val != null && !val.isBlank()) return val.trim();
        }

        return defaultValue;
    }

    private static int getInt(Properties props, String envKey, String dottedKey, int defaultValue) {
        String val = getString(props, envKey, dottedKey, null);
        if (val == null || val.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static boolean getBoolean(Properties props, String envKey, String dottedKey, boolean defaultValue) {
        String val = getString(props, envKey, dottedKey, null);
        if (val == null || val.isBlank()) return defaultValue;
        return val.trim().equalsIgnoreCase("true") || val.trim().equals("1");
    }
}

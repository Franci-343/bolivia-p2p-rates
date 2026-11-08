package dev.joracle.rates.config;

/**
 * Application configuration loaded from environment variables with sensible defaults.
 * Follows the recommendations in docs/PLAN_BOLIVIA_P2P_RATES.md.
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

    public static AppConfig fromEnv() {
        int serverPort = getEnvAsInt("PORT", 8080);
        // Default to 600s (10 min) as recommended in PLAN_BOLIVIA_P2P_RATES.md to protect against WAF blocks
        int pollIntervalSeconds = getEnvAsInt("POLL_INTERVAL_SECONDS", 600);
        int cooldownSeconds = getEnvAsInt("REFRESH_COOLDOWN_SECONDS", 60);
        int binanceRows = getEnvAsInt("BINANCE_FETCH_ROWS", 10);
        String fiat = getEnvOrDefault("FIAT_CURRENCY", "BOB");
        String asset = getEnvOrDefault("ASSET_CURRENCY", "USDT");
        String dbPath = getEnvOrDefault("SQLITE_DB_PATH", "database/rates.db");
        String publicBasePath = normalizeBasePath(getEnvOrDefault("PUBLIC_BASE_PATH", ""));

        // Oracle Cloud Autonomous configuration
        boolean oracleSyncEnabled = getEnvAsBoolean("ORACLE_SYNC_ENABLED", false);
        String oracleJdbcUrl = getEnvOrDefault("ORACLE_JDBC_URL", "jdbc:oracle:thin:@francodb_low");
        String oracleDbUser = getEnvOrDefault("ORACLE_DB_USER", "BOLIVIA_P2P");
        String oracleDbPassword = getEnvOrDefault("ORACLE_DB_PASSWORD", "");
        String oracleTnsAdmin = getEnvOrDefault("ORACLE_TNS_ADMIN", "");
        // Strict invariant: pool size is capped at 1 for this process
        int oraclePoolMaxSize = Math.min(1, Math.max(1, getEnvAsInt("ORACLE_POOL_MAX_SIZE", 1)));
        int oracleSyncIntervalSeconds = Math.max(10, getEnvAsInt("ORACLE_SYNC_INTERVAL_SECONDS", 60));
        int oracleSyncBatchSize = Math.max(1, Math.min(100, getEnvAsInt("ORACLE_SYNC_BATCH_SIZE", 25)));
        int sqliteRecentSnapshots = Math.max(10, getEnvAsInt("SQLITE_RECENT_SNAPSHOTS", 100));

        return new AppConfig(
                serverPort, pollIntervalSeconds, cooldownSeconds, binanceRows,
                fiat, asset, dbPath, publicBasePath,
                oracleSyncEnabled, oracleJdbcUrl, oracleDbUser, oracleDbPassword,
                oracleTnsAdmin, oraclePoolMaxSize, oracleSyncIntervalSeconds,
                oracleSyncBatchSize, sqliteRecentSnapshots
        );
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

    private static int getEnvAsInt(String key, int defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static boolean getEnvAsBoolean(String key, boolean defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim().equalsIgnoreCase("true") || value.trim().equals("1");
    }

    private static String getEnvOrDefault(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value != null && !value.isBlank()) ? value.trim() : defaultValue;
    }
}

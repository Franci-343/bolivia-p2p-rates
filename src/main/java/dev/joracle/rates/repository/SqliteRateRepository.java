package dev.joracle.rates.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.joracle.rates.db.DatabaseManager;
import dev.joracle.rates.model.BankRateSummary;
import dev.joracle.rates.model.MarketSummary;
import dev.joracle.rates.model.P2POffer;
import dev.joracle.rates.model.RateSnapshot;
import dev.joracle.rates.model.TradeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SQLite-backed implementation of {@link RateRepository}.
 * <p>
 * Combines indexed columns for primary financial metrics with structured JSON payloads
 * for payment method breakdowns and offer lists.
 * Maintains an in-memory atomic reference to the latest snapshot to serve consumer REST
 * queries in sub-microseconds without hitting disk on every request.
 */
public class SqliteRateRepository implements RateRepository {

    private static final Logger log = LoggerFactory.getLogger(SqliteRateRepository.class);

    private final DatabaseManager databaseManager;
    private final ObjectMapper objectMapper;
    private final AtomicReference<RateSnapshot> cachedLatest = new AtomicReference<>();

    public SqliteRateRepository(DatabaseManager databaseManager, ObjectMapper objectMapper) {
        this.databaseManager = Objects.requireNonNull(databaseManager, "databaseManager cannot be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper cannot be null");
        // Warm up cache with most recent record from SQLite if available
        findLatestFromDatabase().ifPresent(cachedLatest::set);
    }

    @Override
    public void save(RateSnapshot snapshot) {
        if (snapshot == null) return;

        String insertSql = """
                INSERT INTO rate_snapshots (
                    cycle_id, captured_at, duration_ms, is_stale, status, fiat, asset,
                    buy_reference_price, buy_average_price, buy_median_price, buy_min_price, buy_max_price,
                    buy_valid_count, buy_discarded_count, buy_total_received, buy_details_json,
                    sell_reference_price, sell_average_price, sell_median_price, sell_min_price, sell_max_price,
                    sell_valid_count, sell_discarded_count, sell_total_received, sell_details_json
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?,
                    ?, ?, ?, ?,
                    ?, ?, ?, ?, ?,
                    ?, ?, ?, ?
                )
                """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(insertSql)) {

            MarketSummary buy = snapshot.buyMarket();
            MarketSummary sell = snapshot.sellMarket();

            String buyDetailsJson = serializeDetails(buy.bankSummaries(), buy.topOffers());
            String sellDetailsJson = serializeDetails(sell.bankSummaries(), sell.topOffers());

            int idx = 1;
            pstmt.setLong(idx++, snapshot.cycleId());
            pstmt.setString(idx++, snapshot.timestamp().toString());
            pstmt.setLong(idx++, snapshot.durationMs());
            pstmt.setInt(idx++, snapshot.stale() ? 1 : 0);
            pstmt.setString(idx++, snapshot.status());
            pstmt.setString(idx++, buy.fiat());
            pstmt.setString(idx++, buy.asset());

            // Buy fields
            pstmt.setString(idx++, buy.referencePrice().toPlainString());
            pstmt.setString(idx++, buy.averagePrice().toPlainString());
            pstmt.setString(idx++, buy.medianPrice().toPlainString());
            pstmt.setString(idx++, buy.minPrice().toPlainString());
            pstmt.setString(idx++, buy.maxPrice().toPlainString());
            pstmt.setInt(idx++, buy.validCount());
            pstmt.setInt(idx++, buy.discardedCount());
            pstmt.setInt(idx++, buy.totalReceived());
            pstmt.setString(idx++, buyDetailsJson);

            // Sell fields
            pstmt.setString(idx++, sell.referencePrice().toPlainString());
            pstmt.setString(idx++, sell.averagePrice().toPlainString());
            pstmt.setString(idx++, sell.medianPrice().toPlainString());
            pstmt.setString(idx++, sell.minPrice().toPlainString());
            pstmt.setString(idx++, sell.maxPrice().toPlainString());
            pstmt.setInt(idx++, sell.validCount());
            pstmt.setInt(idx++, sell.discardedCount());
            pstmt.setInt(idx++, sell.totalReceived());
            pstmt.setString(idx++, sellDetailsJson);

            pstmt.executeUpdate();

            // Update in-memory atomic reference for instantaneous API reads
            cachedLatest.set(snapshot);

        } catch (SQLException e) {
            log.error("Failed to persist rate snapshot for cycle #{}: {}", snapshot.cycleId(), e.getMessage(), e);
            // Even if disk write fails, update in-memory cache so current traffic is not interrupted
            cachedLatest.set(snapshot);
        }
    }

    @Override
    public Optional<RateSnapshot> findLatest() {
        RateSnapshot cached = cachedLatest.get();
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<RateSnapshot> fromDb = findLatestFromDatabase();
        fromDb.ifPresent(cachedLatest::set);
        return fromDb;
    }

    @Override
    public List<RateSnapshot> findRecent(int limit) {
        int targetLimit = Math.max(1, Math.min(limit, 500));
        String sql = "SELECT * FROM rate_snapshots ORDER BY id DESC LIMIT ?";

        List<RateSnapshot> results = new ArrayList<>();
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, targetLimit);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    results.add(mapRowToSnapshot(rs));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to query recent rate snapshots from SQLite: {}", e.getMessage(), e);
            // Fallback to cached item if DB query fails
            RateSnapshot cached = cachedLatest.get();
            if (cached != null) {
                return List.of(cached);
            }
        }
        return Collections.unmodifiableList(results);
    }

    private Optional<RateSnapshot> findLatestFromDatabase() {
        String sql = "SELECT * FROM rate_snapshots ORDER BY id DESC LIMIT 1";
        try (Connection conn = databaseManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            if (rs.next()) {
                return Optional.of(mapRowToSnapshot(rs));
            }
        } catch (SQLException e) {
            log.warn("Could not retrieve latest snapshot from SQLite: {}", e.getMessage());
        }
        return Optional.empty();
    }

    private RateSnapshot mapRowToSnapshot(ResultSet rs) throws SQLException {
        long cycleId = rs.getLong("cycle_id");
        Instant capturedAt = Instant.parse(rs.getString("captured_at"));
        long durationMs = rs.getLong("duration_ms");
        boolean isStale = rs.getInt("is_stale") == 1;
        String status = rs.getString("status");
        String fiat = rs.getString("fiat");
        String asset = rs.getString("asset");

        MarketDetails buyDetails = deserializeDetails(rs.getString("buy_details_json"));
        MarketSummary buyMarket = new MarketSummary(
                TradeType.BUY,
                fiat,
                asset,
                new BigDecimal(rs.getString("buy_reference_price")),
                new BigDecimal(rs.getString("buy_average_price")),
                new BigDecimal(rs.getString("buy_median_price")),
                new BigDecimal(rs.getString("buy_min_price")),
                new BigDecimal(rs.getString("buy_max_price")),
                rs.getInt("buy_total_received"),
                rs.getInt("buy_valid_count"),
                rs.getInt("buy_discarded_count"),
                buyDetails.bankSummaries(),
                buyDetails.topOffers()
        );

        MarketDetails sellDetails = deserializeDetails(rs.getString("sell_details_json"));
        MarketSummary sellMarket = new MarketSummary(
                TradeType.SELL,
                fiat,
                asset,
                new BigDecimal(rs.getString("sell_reference_price")),
                new BigDecimal(rs.getString("sell_average_price")),
                new BigDecimal(rs.getString("sell_median_price")),
                new BigDecimal(rs.getString("sell_min_price")),
                new BigDecimal(rs.getString("sell_max_price")),
                rs.getInt("sell_total_received"),
                rs.getInt("sell_valid_count"),
                rs.getInt("sell_discarded_count"),
                sellDetails.bankSummaries(),
                sellDetails.topOffers()
        );

        return new RateSnapshot(
                cycleId,
                capturedAt,
                durationMs,
                isStale,
                status,
                buyMarket,
                sellMarket
        );
    }

    private String serializeDetails(List<BankRateSummary> bankSummaries, List<P2POffer> topOffers) {
        try {
            return objectMapper.writeValueAsString(new MarketDetails(
                    bankSummaries != null ? bankSummaries : List.of(),
                    topOffers != null ? topOffers : List.of()
            ));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize market details to JSON: {}", e.getMessage());
            return "{\"bankSummaries\":[],\"topOffers\":[]}";
        }
    }

    private MarketDetails deserializeDetails(String json) {
        if (json == null || json.isBlank()) {
            return new MarketDetails(List.of(), List.of());
        }
        try {
            return objectMapper.readValue(json, MarketDetails.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize market details JSON: {}", e.getMessage());
            return new MarketDetails(List.of(), List.of());
        }
    }

    public record MarketDetails(
            List<BankRateSummary> bankSummaries,
            List<P2POffer> topOffers
    ) {}
}

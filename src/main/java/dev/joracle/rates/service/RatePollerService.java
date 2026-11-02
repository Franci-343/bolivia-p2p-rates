package dev.joracle.rates.service;

import dev.joracle.rates.client.BinanceApiException;
import dev.joracle.rates.client.BinanceP2PClient;
import dev.joracle.rates.config.AppConfig;
import dev.joracle.rates.model.BankRateSummary;
import dev.joracle.rates.model.MarketSummary;
import dev.joracle.rates.model.P2POffer;
import dev.joracle.rates.model.RateSnapshot;
import dev.joracle.rates.model.TradeType;
import dev.joracle.rates.repository.RateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Worker service responsible for scheduled polling, analysis orchestration,
 * repository persistence, and visual log presentation.
 * <p>
 * Core Architecture Rule:
 * This poller is the ONLY component allowed to make outbound requests to Binance.
 * Consumer queries read purely from {@link RateRepository}.
 */
public class RatePollerService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RatePollerService.class);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("America/La_Paz"));

    private final BinanceP2PClient binanceClient;
    private final RateAnalysisService analysisService;
    private final RateRepository rateRepository;
    private final AppConfig config;
    private final ScheduledExecutorService scheduler;

    private final AtomicLong cycleCounter = new AtomicLong(0);
    private final AtomicBoolean isPolling = new AtomicBoolean(false);
    private volatile Instant lastPollInstant = Instant.EPOCH;

    public RatePollerService(
            BinanceP2PClient binanceClient,
            RateAnalysisService analysisService,
            RateRepository rateRepository,
            AppConfig config
    ) {
        this.binanceClient = Objects.requireNonNull(binanceClient, "binanceClient cannot be null");
        this.analysisService = Objects.requireNonNull(analysisService, "analysisService cannot be null");
        this.rateRepository = Objects.requireNonNull(rateRepository, "rateRepository cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rate-poller-worker");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Starts the scheduled background polling task.
     */
    public void start() {
        log.info("Starting RatePollerService. Interval: {}s. Cooldown: {}s. Pair: {}/{}",
                config.pollIntervalSeconds(), config.minRefreshCooldownSeconds(),
                config.fiatCurrency(), config.assetCurrency());

        // Initial fetch with delay 0, then repeated every N seconds
        scheduler.scheduleWithFixedDelay(
                this::pollAndLogSafe,
                0,
                config.pollIntervalSeconds(),
                TimeUnit.SECONDS
        );
    }

    /**
     * Executes a single poll cycle synchronously.
     * Prevents overlapping cycles and updates repository with fresh or stale snapshots.
     */
    public RateSnapshot pollOnce() {
        if (!isPolling.compareAndSet(false, true)) {
            log.warn("Poll cycle already in progress, returning latest repository snapshot.");
            return rateRepository.findLatest().orElse(null);
        }

        long cycleId = cycleCounter.incrementAndGet();
        long startTime = System.currentTimeMillis();

        try {
            log.info("Starting Cycle #{} for {}/{}...", cycleId, config.fiatCurrency(), config.assetCurrency());

            List<P2POffer> buyOffers = binanceClient.fetchOffers(TradeType.BUY);
            List<P2POffer> sellOffers = binanceClient.fetchOffers(TradeType.SELL);

            MarketSummary buyMarket = analysisService.analyzeMarket(
                    buyOffers,
                    TradeType.BUY,
                    config.fiatCurrency(),
                    config.assetCurrency()
            );

            MarketSummary sellMarket = analysisService.analyzeMarket(
                    sellOffers,
                    TradeType.SELL,
                    config.fiatCurrency(),
                    config.assetCurrency()
            );

            long durationMs = System.currentTimeMillis() - startTime;
            Instant now = Instant.now();
            lastPollInstant = now;

            RateSnapshot snapshot = new RateSnapshot(
                    cycleId,
                    now,
                    durationMs,
                    false,
                    "OK",
                    buyMarket,
                    sellMarket
            );

            rateRepository.save(snapshot);
            logMarketSnapshot(snapshot);
            return snapshot;

        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startTime;
            log.warn("Cycle #{} failed after {} ms: {}. Protecting current repository snapshot.",
                    cycleId, durationMs, e.getMessage());

            // Mark existing snapshot as STALE if available so clients know data is older
            rateRepository.findLatest().ifPresent(current -> {
                RateSnapshot stale = current.markStale();
                rateRepository.save(stale);
                log.warn("[AVISO] El snapshot en memoria ha sido marcado como STALE (desactualizado).");
            });

            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new BinanceApiException("Unexpected error during cycle #" + cycleId + ": " + e.getMessage(), e);
        } finally {
            isPolling.set(false);
        }
    }

    /**
     * Manual refresh with cooldown protection.
     * If called before cooldown expires, returns cached snapshot without calling Binance.
     */
    public RefreshResult refreshWithCooldown() {
        long secondsSinceLastPoll = Duration.between(lastPollInstant, Instant.now()).getSeconds();
        if (secondsSinceLastPoll < config.minRefreshCooldownSeconds()) {
            long remainingCooldown = config.minRefreshCooldownSeconds() - secondsSinceLastPoll;
            log.info("Manual refresh blocked by cooldown ({}s remaining). Serving cached repository data.", remainingCooldown);
            RateSnapshot cached = rateRepository.findLatest().orElse(null);
            return new RefreshResult(false, remainingCooldown, "Cooldown active: serving cached snapshot", cached);
        }

        RateSnapshot fresh = pollOnce();
        return new RefreshResult(true, 0, "Fresh snapshot generated from Binance", fresh);
    }

    public record RefreshResult(
            boolean executed,
            long cooldownRemainingSeconds,
            String message,
            RateSnapshot snapshot
    ) {}

    private void pollAndLogSafe() {
        try {
            pollOnce();
        } catch (BinanceApiException e) {
            log.warn("Binance P2P polling network issue: {}", e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error during rate polling cycle: {}", e.getMessage(), e);
        }
    }

    private void logMarketSnapshot(RateSnapshot snapshot) {
        String formattedTime = TIME_FORMATTER.format(snapshot.timestamp());
        MarketSummary buy = snapshot.buyMarket();
        MarketSummary sell = snapshot.sellMarket();

        StringBuilder sb = new StringBuilder();
        sb.append("\n========================================================================================\n");
        sb.append(String.format("  BINANCE P2P MONITOR [%s / %s] - CICLO #%04d (%s Hora Bolivia)\n",
                config.assetCurrency(), config.fiatCurrency(), snapshot.cycleId(), formattedTime));
        sb.append(String.format("  Estado: %s | Duración: %d ms | Próximo ciclo en: %d s | Datos: %s\n",
                snapshot.status(), snapshot.durationMs(), config.pollIntervalSeconds(),
                snapshot.stale() ? "DESACTUALIZADOS (STALE)" : "FRESCOS"));
        sb.append("----------------------------------------------------------------------------------------\n");

        // Buy section
        sb.append(" 🟢 COMPRA (Usuario compra USDT pagando Bolivianos):\n");
        sb.append(String.format("   * Referencia (Mediana) : %s %s\n", buy.referencePrice(), buy.fiat()));
        sb.append(String.format("   * Promedio muestral    : %s %s\n", buy.averagePrice(), buy.fiat()));
        sb.append(String.format("   * Rango observado      : [%s - %s] %s\n", buy.minPrice(), buy.maxPrice(), buy.fiat()));
        sb.append(String.format("   * Muestra de anuncios  : %d recibidos (%d válidos, %d descartados)\n",
                buy.totalReceived(), buy.validCount(), buy.discardedCount()));
        sb.append("   * Métodos observados   : ").append(formatBankList(buy.bankSummaries())).append("\n");

        sb.append("----------------------------------------------------------------------------------------\n");

        // Sell section
        sb.append(" 🔴 VENTA (Usuario vende USDT recibiendo Bolivianos):\n");
        sb.append(String.format("   * Referencia (Mediana) : %s %s\n", sell.referencePrice(), sell.fiat()));
        sb.append(String.format("   * Promedio muestral    : %s %s\n", sell.averagePrice(), sell.fiat()));
        sb.append(String.format("   * Rango observado      : [%s - %s] %s\n", sell.minPrice(), sell.maxPrice(), sell.fiat()));
        sb.append(String.format("   * Muestra de anuncios  : %d recibidos (%d válidos, %d descartados)\n",
                sell.totalReceived(), sell.validCount(), sell.discardedCount()));
        sb.append("   * Métodos observados   : ").append(formatBankList(sell.bankSummaries())).append("\n");

        sb.append("========================================================================================");

        log.info("{}", sb);
    }

    private String formatBankList(List<BankRateSummary> bankSummaries) {
        if (bankSummaries == null || bankSummaries.isEmpty()) {
            return "Ninguno observado";
        }
        StringBuilder b = new StringBuilder();
        int count = 0;
        for (BankRateSummary bs : bankSummaries) {
            if (count > 0) b.append(", ");
            b.append(bs.bankName()).append(" (~").append(bs.averagePrice()).append(")");
            count++;
            if (count >= 5) break; // Top 5
        }
        return b.toString();
    }

    @Override
    public void close() {
        log.info("Shutting down RatePollerService scheduler...");
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}

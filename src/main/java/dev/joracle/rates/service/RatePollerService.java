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

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Worker service responsible for scheduled polling, analysis orchestration,
 * repository persistence, and visual log presentation.
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
        log.info("Starting RatePollerService. Interval: {} seconds. Currency: {}/{}",
                config.pollIntervalSeconds(), config.fiatCurrency(), config.assetCurrency());

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
     */
    public RateSnapshot pollOnce() {
        log.info("Executing rate fetch cycle for {}/{}...", config.fiatCurrency(), config.assetCurrency());

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

        RateSnapshot snapshot = new RateSnapshot(Instant.now(), buyMarket, sellMarket);
        rateRepository.save(snapshot);

        logMarketSnapshot(snapshot);
        return snapshot;
    }

    private void pollAndLogSafe() {
        try {
            pollOnce();
        } catch (BinanceApiException e) {
            log.warn("Binance P2P polling error: {}", e.getMessage());
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
        sb.append(String.format("  BINANCE P2P MONITOR [%s / %s] - %s (Hora Bolivia)\n",
                config.assetCurrency(), config.fiatCurrency(), formattedTime));
        sb.append("----------------------------------------------------------------------------------------\n");

        // Buy section (user buys USDT paying BOB)
        sb.append(" [COMPRA / BUY] Usuario compra USDT pagando Bolivianos:\n");
        sb.append(String.format("   * Promedio : %s %s\n", buy.averagePrice(), buy.fiat()));
        sb.append(String.format("   * Mediana  : %s %s\n", buy.medianPrice(), buy.fiat()));
        sb.append(String.format("   * Rango    : [%s - %s] %s (Muestras: %d)\n",
                buy.minPrice(), buy.maxPrice(), buy.fiat(), buy.sampleCount()));
        sb.append("   * Bancos   : ").append(formatBankList(buy.bankSummaries())).append("\n");

        sb.append("----------------------------------------------------------------------------------------\n");

        // Sell section (user sells USDT receiving BOB)
        sb.append(" [VENTA / SELL] Usuario vende USDT recibiendo Bolivianos:\n");
        sb.append(String.format("   * Promedio : %s %s\n", sell.averagePrice(), sell.fiat()));
        sb.append(String.format("   * Mediana  : %s %s\n", sell.medianPrice(), sell.fiat()));
        sb.append(String.format("   * Rango    : [%s - %s] %s (Muestras: %d)\n",
                sell.minPrice(), sell.maxPrice(), sell.fiat(), sell.sampleCount()));
        sb.append("   * Bancos   : ").append(formatBankList(sell.bankSummaries())).append("\n");

        sb.append("========================================================================================");

        log.info("{}", sb);
    }

    private String formatBankList(List<BankRateSummary> bankSummaries) {
        if (bankSummaries == null || bankSummaries.isEmpty()) {
            return "Ninguno reportado";
        }
        StringBuilder b = new StringBuilder();
        int count = 0;
        for (BankRateSummary bs : bankSummaries) {
            if (count > 0) b.append(", ");
            b.append(bs.bankName()).append(" (~").append(bs.averagePrice()).append(")");
            count++;
            if (count >= 5) break; // Top 5 banks
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

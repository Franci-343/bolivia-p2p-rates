package dev.joracle.rates;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.joracle.rates.api.RateController;
import dev.joracle.rates.client.BinanceP2PClient;
import dev.joracle.rates.config.AppConfig;
import dev.joracle.rates.config.JsonConfig;
import dev.joracle.rates.repository.InMemoryRateRepository;
import dev.joracle.rates.repository.RateRepository;
import dev.joracle.rates.service.RateAnalysisService;
import dev.joracle.rates.service.RatePollerService;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main application bootstrap for Bolivia P2P Rates Monitor.
 * Inspired by modern Spring Boot layered architecture and idiomatic Java 25.
 */
public class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        log.info("Starting Bolivia P2P Rates Service (Java 25 + Javalin 7)...");

        // 1. Configuration
        AppConfig config = AppConfig.fromEnv();
        ObjectMapper objectMapper = JsonConfig.createObjectMapper();

        // 2. Dependency Injection (Composition Root)
        BinanceP2PClient binanceClient = new BinanceP2PClient(objectMapper, config);
        RateAnalysisService analysisService = new RateAnalysisService();
        // In-memory repository for now; easily swappable with OracleRateRepository in Phase 2
        RateRepository rateRepository = new InMemoryRateRepository(100);

        RatePollerService pollerService = new RatePollerService(
                binanceClient,
                analysisService,
                rateRepository,
                config
        );

        RateController rateController = new RateController(rateRepository, pollerService);

        // 3. Web Framework (Javalin 7 with Virtual Threads)
        Javalin app = Javalin.create(javalinConfig -> {
            javalinConfig.jsonMapper(new JavalinJackson(objectMapper, false));
            javalinConfig.concurrency.useVirtualThreads = true;
            javalinConfig.startup.showJavalinBanner = false;
            rateController.registerRoutes(javalinConfig.routes);
        });

        app.start(config.serverPort());

        log.info("Server started on port http://localhost:{}", config.serverPort());
        log.info("Available endpoints (100% served from Database / InMemory repository):");
        log.info("  GET  /health");
        log.info("  GET  /api/v1/status");
        log.info("  GET  /api/v1/rates/latest");
        log.info("  GET  /api/v1/rates/buy");
        log.info("  GET  /api/v1/rates/sell");
        log.info("  GET  /api/v1/rates/history");
        log.info("  POST /api/v1/rates/refresh (protected with cooldown)");

        // 4. Start Background Poller Worker
        pollerService.start();

        // 5. Graceful Shutdown Hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down Bolivia P2P Rates Service...");
            pollerService.close();
            app.stop();
            log.info("Service stopped gracefully.");
        }, "shutdown-hook"));
    }
}

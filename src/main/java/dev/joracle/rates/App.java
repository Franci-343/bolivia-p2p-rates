package dev.joracle.rates;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.joracle.rates.api.AdminController;
import dev.joracle.rates.api.PortalController;
import dev.joracle.rates.api.RateController;
import dev.joracle.rates.client.BinanceP2PClient;
import dev.joracle.rates.config.AppConfig;
import dev.joracle.rates.config.JsonConfig;
import dev.joracle.rates.config.ThymeleafConfig;
import dev.joracle.rates.db.DatabaseManager;
import dev.joracle.rates.db.DatabaseMigrator;
import dev.joracle.rates.repository.ApiClientRepository;
import dev.joracle.rates.repository.RateRepository;
import dev.joracle.rates.repository.SqliteApiClientRepository;
import dev.joracle.rates.repository.SqliteRateRepository;
import dev.joracle.rates.service.ApiKeyService;
import dev.joracle.rates.service.RateAnalysisService;
import dev.joracle.rates.service.RatePollerService;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;
import io.javalin.rendering.FileRenderer;
import io.javalin.rendering.template.JavalinThymeleaf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Main application bootstrap for Bolivia P2P Rates Monitor.
 * Inspired by modern Spring Boot layered architecture and idiomatic Java 25.
 */
public class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        log.info("Starting Bolivia P2P Rates Service (Java 25 + Javalin 7 + SQLite + HTMX)...");

        // 1. Configuration & JSON
        AppConfig config = AppConfig.fromEnv();
        ObjectMapper objectMapper = JsonConfig.createObjectMapper();

        // 2. Database Persistence & Migrations
        DatabaseManager databaseManager = new DatabaseManager(config.databasePath());
        DatabaseMigrator migrator = new DatabaseMigrator(databaseManager, Path.of("database"));
        migrator.migrate();

        // 3. Dependency Injection (Composition Root)
        BinanceP2PClient binanceClient = new BinanceP2PClient(objectMapper, config);
        RateAnalysisService analysisService = new RateAnalysisService();
        RateRepository rateRepository = new SqliteRateRepository(databaseManager, objectMapper);
        ApiClientRepository clientRepository = new SqliteApiClientRepository(databaseManager);
        ApiKeyService apiKeyService = new ApiKeyService(clientRepository);
        String envAdminKey = System.getenv("ADMIN_API_KEY");
        if (envAdminKey != null && !envAdminKey.isBlank()) {
            apiKeyService.ensureAdminKey(envAdminKey.trim());
        }

        RatePollerService pollerService = new RatePollerService(
                binanceClient,
                analysisService,
                rateRepository,
                config
        );

        dev.joracle.rates.service.AdminSessionService sessionService = new dev.joracle.rates.service.AdminSessionService();
        RateController rateController = new RateController(rateRepository, pollerService, apiKeyService);
        AdminController adminController = new AdminController(rateRepository, pollerService, apiKeyService, config, sessionService);
        PortalController portalController = new PortalController(rateRepository, apiKeyService, config);

        // 4. Web Framework (Javalin 7 with Virtual Threads & Thymeleaf)
        FileRenderer thymeleafRenderer = new JavalinThymeleaf(ThymeleafConfig.createTemplateEngine());
        Javalin app = Javalin.create(javalinConfig -> {
            javalinConfig.jsonMapper(new JavalinJackson(objectMapper, false));
            javalinConfig.fileRenderer((filePath, model, context) -> {
                Map<String, Object> enrichedModel = new HashMap<>(model);
                enrichedModel.putIfAbsent("publicBasePath", config.publicBasePath());
                return thymeleafRenderer.render(filePath, enrichedModel, context);
            });
            javalinConfig.concurrency.useVirtualThreads = true;
            javalinConfig.startup.showJavalinBanner = false;
            portalController.registerRoutes(javalinConfig.routes);
            rateController.registerRoutes(javalinConfig.routes);
            adminController.registerRoutes(javalinConfig.routes);
        });

        app.start(config.serverPort());

        log.info("Server started on port http://localhost:{}", config.serverPort());
        if (!config.publicBasePath().isEmpty()) {
            log.info("Configured Public Base Path: {}", config.publicBasePath());
        }
        log.info("Public Developer Portal & Wiki (HTMX + Thymeleaf):");
        log.info("  GET  http://localhost:{}/ (Portal de Desarrolladores & Wiki)", config.serverPort());
        log.info("  GET  http://localhost:{}/docs (Documentación de Endpoints)", config.serverPort());
        log.info("  POST http://localhost:{}/portal/keys (Generación de API Keys)", config.serverPort());
        log.info("Web Dashboard (HTMX + Thymeleaf):");
        log.info("  GET  http://localhost:{}/admin (Panel de Administrador)", config.serverPort());
        log.info("REST Endpoints (Protected by X-API-Key):");
        log.info("  GET  /health (Public)");
        log.info("  GET  /api/v1/status (Public)");
        log.info("  GET  /api/v1/rates/latest");
        log.info("  GET  /api/v1/rates/buy");
        log.info("  GET  /api/v1/rates/sell");
        log.info("  GET  /api/v1/rates/history");
        log.info("  POST /api/v1/rates/refresh (ADMIN only)");

        // 5. Start Background Poller Worker
        pollerService.start();

        // 6. Graceful Shutdown Hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down Bolivia P2P Rates Service...");
            pollerService.close();
            app.stop();
            databaseManager.close();
            log.info("Service stopped gracefully.");
        }, "shutdown-hook"));
    }
}

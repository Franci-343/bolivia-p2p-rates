package dev.joracle.rates.api;

import dev.joracle.rates.client.BinanceApiException;
import dev.joracle.rates.model.RateSnapshot;
import dev.joracle.rates.repository.RateRepository;
import dev.joracle.rates.service.RatePollerService;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * REST controller exposing endpoints for consumers and developers.
 * <p>
 * Core Architecture Rule:
 * All consumer requests read strictly from {@link RateRepository} (In-Memory buffer / Oracle DB).
 * Zero requests are forwarded to Binance when clients query this API.
 */
public class RateController {

    private final RateRepository rateRepository;
    private final RatePollerService pollerService;

    public RateController(RateRepository rateRepository, RatePollerService pollerService) {
        this.rateRepository = Objects.requireNonNull(rateRepository, "rateRepository cannot be null");
        this.pollerService = Objects.requireNonNull(pollerService, "pollerService cannot be null");
    }

    /**
     * Registers all routes and global exception handlers on Javalin 7's RoutesConfig.
     */
    public void registerRoutes(RoutesConfig routes) {
        // Health & Status endpoints
        routes.get("/health", this::healthCheck);
        routes.get("/api/v1/status", this::getStatus);

        // Core rate endpoints (all served strictly from repository)
        routes.get("/api/v1/rates/latest", this::getLatestRates);
        routes.get("/api/v1/rates/buy", this::getBuyRates);
        routes.get("/api/v1/rates/sell", this::getSellRates);
        routes.get("/api/v1/rates/history", this::getRecentHistory);

        // Protected refresh with strict cooldown
        routes.post("/api/v1/rates/refresh", this::refreshRates);

        // Global Exception Handlers
        routes.exception(BinanceApiException.class, (e, ctx) -> {
            ctx.status(HttpStatus.BAD_GATEWAY).json(Map.of(
                    "error", "Binance API Communication Failure",
                    "message", e.getMessage(),
                    "timestamp", Instant.now().toString()
            ));
        });

        routes.exception(Exception.class, (e, ctx) -> {
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(Map.of(
                    "error", "Internal Server Error",
                    "message", e.getMessage() != null ? e.getMessage() : "Unknown error",
                    "timestamp", Instant.now().toString()
            ));
        });
    }

    private void healthCheck(Context ctx) {
        boolean hasData = rateRepository.findLatest().isPresent();
        ctx.status(HttpStatus.OK).json(Map.of(
                "status", "UP",
                "timestamp", Instant.now().toString(),
                "hasSnapshot", hasData
        ));
    }

    private void getStatus(Context ctx) {
        rateRepository.findLatest().ifPresentOrElse(
                latest -> {
                    long ageSeconds = Duration.between(latest.timestamp(), Instant.now()).getSeconds();
                    ctx.status(HttpStatus.OK).json(Map.of(
                            "status", "UP",
                            "cycleId", latest.cycleId(),
                            "lastCycleStatus", latest.status(),
                            "isStale", latest.stale(),
                            "dataAgeSeconds", ageSeconds,
                            "lastCycleDurationMs", latest.durationMs(),
                            "capturedAt", latest.timestamp().toString()
                    ));
                },
                () -> ctx.status(HttpStatus.OK).json(Map.of(
                        "status", "INITIALIZING",
                        "message", "Awaiting first scheduled collection cycle"
                ))
        );
    }

    private void getLatestRates(Context ctx) {
        rateRepository.findLatest().ifPresentOrElse(
                ctx::json,
                () -> ctx.status(HttpStatus.SERVICE_UNAVAILABLE).json(Map.of(
                        "status", "PENDING",
                        "message", "El primer snapshot de mercado se está recolectando. Reintente en unos segundos."
                ))
        );
    }

    private void getBuyRates(Context ctx) {
        rateRepository.findLatest().map(RateSnapshot::buyMarket).ifPresentOrElse(
                ctx::json,
                () -> ctx.status(HttpStatus.SERVICE_UNAVAILABLE).json(Map.of(
                        "status", "PENDING",
                        "message", "Datos de mercado aún no disponibles."
                ))
        );
    }

    private void getSellRates(Context ctx) {
        rateRepository.findLatest().map(RateSnapshot::sellMarket).ifPresentOrElse(
                ctx::json,
                () -> ctx.status(HttpStatus.SERVICE_UNAVAILABLE).json(Map.of(
                        "status", "PENDING",
                        "message", "Datos de mercado aún no disponibles."
                ))
        );
    }

    private void getRecentHistory(Context ctx) {
        int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(10);
        List<RateSnapshot> history = rateRepository.findRecent(Math.min(limit, 100));
        ctx.json(history);
    }

    private void refreshRates(Context ctx) {
        RatePollerService.RefreshResult result = pollerService.refreshWithCooldown();
        if (!result.executed()) {
            ctx.header("X-Cache", "HIT-COOLDOWN");
            ctx.status(HttpStatus.OK).json(Map.of(
                    "status", "COOLDOWN_ACTIVE",
                    "message", result.message(),
                    "cooldownRemainingSeconds", result.cooldownRemainingSeconds(),
                    "cachedSnapshot", result.snapshot() != null ? result.snapshot() : Map.of()
            ));
        } else {
            ctx.header("X-Cache", "MISS-FRESH");
            ctx.status(HttpStatus.OK).json(result.snapshot());
        }
    }
}

package dev.joracle.rates.api;

import dev.joracle.rates.config.AppConfig;
import dev.joracle.rates.model.ClientCreationResult;
import dev.joracle.rates.model.RateSnapshot;
import dev.joracle.rates.repository.RateRepository;
import dev.joracle.rates.service.ApiKeyService;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Controller serving the public Developer Portal, interactive API Wiki,
 * and self-service API key registration with IP rate limiting.
 */
public class PortalController {

    private static final Logger log = LoggerFactory.getLogger(PortalController.class);

    private static final int MAX_KEYS_PER_IP_PER_HOUR = 5;
    private static final Duration IP_WINDOW = Duration.ofHours(1);
    private static final int PUBLIC_TIER_RPM = 10;

    private final RateRepository rateRepository;
    private final ApiKeyService apiKeyService;
    private final AppConfig config;
    private final ConcurrentHashMap<String, Deque<Instant>> ipRegistrationHistory = new ConcurrentHashMap<>();

    public PortalController(RateRepository rateRepository, ApiKeyService apiKeyService, AppConfig config) {
        this.rateRepository = Objects.requireNonNull(rateRepository, "rateRepository cannot be null");
        this.apiKeyService = Objects.requireNonNull(apiKeyService, "apiKeyService cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
    }

    public void registerRoutes(RoutesConfig routes) {
        routes.get("/", this::renderPortal);
        routes.get("/docs", this::renderPortal);
        routes.post("/portal/keys", this::handlePublicCreateKey);
    }

    private void renderPortal(Context ctx) {
        RateSnapshot snapshot = rateRepository.findLatest().orElse(null);
        Map<String, Object> model = new HashMap<>();
        model.put("snapshot", snapshot);
        model.put("defaultDemoKey", "bolivia_live_demo12345");
        model.put("baseUrl", resolveBaseUrl(ctx));
        model.put("serverPort", config.serverPort());
        ctx.render("portal.html", model);
    }

    private void handlePublicCreateKey(Context ctx) {
        String clientIp = resolveClientIp(ctx);
        String baseUrl = resolveBaseUrl(ctx);
        if (!tryAcquireIpSlot(clientIp)) {
            log.warn("Public API key registration rate limit exceeded for IP: {}", clientIp);
            Map<String, Object> model = new HashMap<>();
            model.put("success", false);
            model.put("errorMessage", "Has alcanzado el límite de 5 API Keys por hora desde tu dirección IP. Intenta más tarde o contáctanos si requieres cuota adicional.");
            model.put("creationResult", null);
            model.put("baseUrl", baseUrl);
            ctx.render("fragments/public-key-result.html", model);
            return;
        }

        String rawName = ctx.formParam("clientName");
        if (rawName == null || rawName.trim().length() < 2) {
            Map<String, Object> model = new HashMap<>();
            model.put("success", false);
            model.put("errorMessage", "Por favor ingresa un nombre o identificador válido para tu aplicación (mínimo 2 caracteres).");
            model.put("creationResult", null);
            model.put("baseUrl", baseUrl);
            ctx.render("fragments/public-key-result.html", model);
            return;
        }

        String sanitizedName = rawName.trim();
        if (sanitizedName.length() > 50) {
            sanitizedName = sanitizedName.substring(0, 50);
        }

        // Public tier keys are locked to CONSUMER role and 10 RPM
        ClientCreationResult creationResult = apiKeyService.createClient(sanitizedName, "CONSUMER", PUBLIC_TIER_RPM);
        log.info("Self-service API Key created for '{}' from IP: {}", sanitizedName, clientIp);

        Map<String, Object> model = new HashMap<>();
        model.put("success", true);
        model.put("errorMessage", null);
        model.put("creationResult", creationResult);
        model.put("baseUrl", baseUrl);
        model.put("serverPort", config.serverPort());
        ctx.render("fragments/public-key-result.html", model);
    }

    synchronized boolean tryAcquireIpSlot(String ip) {
        Instant now = Instant.now();
        Instant cutoff = now.minus(IP_WINDOW);

        Deque<Instant> deque = ipRegistrationHistory.computeIfAbsent(ip, k -> new ArrayDeque<>());
        while (!deque.isEmpty() && deque.peekFirst().isBefore(cutoff)) {
            deque.pollFirst();
        }

        if (deque.size() >= MAX_KEYS_PER_IP_PER_HOUR) {
            return false;
        }

        deque.addLast(now);

        // Periodic cleanup if map grows large to preserve memory on 1GB VPS
        if (ipRegistrationHistory.size() > 1000) {
            ipRegistrationHistory.entrySet().removeIf(entry -> {
                Deque<Instant> d = entry.getValue();
                return d.isEmpty() || d.peekLast().isBefore(cutoff);
            });
        }

        return true;
    }

    private String resolveClientIp(Context ctx) {
        String xForwardedFor = ctx.header("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        String realIp = ctx.header("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return ctx.ip();
    }

    private String resolveBaseUrl(Context ctx) {
        String host = ctx.header("Host");
        if (host == null || host.isBlank()) {
            host = "155.181.158.36:" + config.serverPort();
        }
        String scheme = ctx.scheme();
        if (scheme == null || scheme.isBlank()) {
            scheme = "http";
        }
        return scheme + "://" + host;
    }
}

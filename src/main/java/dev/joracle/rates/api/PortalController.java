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

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Controller serving the public Developer Portal, interactive API Wiki,
 * and self-service API key registration with IP rate limiting and security guards.
 */
public class PortalController {

    private static final Logger log = LoggerFactory.getLogger(PortalController.class);

    private static final int MAX_KEYS_PER_IP_PER_HOUR = 5;
    private static final Duration IP_WINDOW = Duration.ofHours(1);
    private static final Duration IP_BURST_COOLDOWN = Duration.ofSeconds(5);
    private static final int GLOBAL_MAX_KEYS_PER_HOUR = 50;
    private static final int PUBLIC_TIER_RPM = 10;
    private static final Pattern VALID_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9áéíóúÁÉÍÓÚñÑüÜ _.-]{2,40}$");

    private final RateRepository rateRepository;
    private final ApiKeyService apiKeyService;
    private final AppConfig config;

    private final ConcurrentHashMap<String, Deque<Instant>> ipRegistrationHistory = new ConcurrentHashMap<>();
    private final Deque<Instant> globalRegistrationHistory = new ArrayDeque<>();

    public enum LimitStatus {
        ALLOWED,
        COOLDOWN_ACTIVE,
        IP_LIMIT_EXCEEDED,
        GLOBAL_LIMIT_EXCEEDED
    }

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
        model.put("publicBasePath", config.publicBasePath());
        model.put("serverPort", config.serverPort());
        ctx.render("portal.html", model);
    }

    private void handlePublicCreateKey(Context ctx) {
        String baseUrl = resolveBaseUrl(ctx);

        // 1. Cross-origin request verification
        if (!isOriginAllowed(ctx)) {
            log.warn("Public key registration rejected due to invalid Origin/Referer");
            renderKeyError(ctx, baseUrl, "Origen de la solicitud no autorizado.");
            return;
        }

        // 2. Client IP Resolution & Rate Limit Enforcement
        String clientIp = resolveClientIp(ctx);
        LimitStatus limitStatus = tryAcquireSlot(clientIp);
        if (limitStatus != LimitStatus.ALLOWED) {
            String errorMsg = switch (limitStatus) {
                case COOLDOWN_ACTIVE -> "Por favor espera unos segundos antes de generar otra API Key.";
                case GLOBAL_LIMIT_EXCEEDED -> "El límite global de generación de API Keys del servidor gratuito ha sido alcanzado por esta hora. Intenta más tarde.";
                default -> "Has alcanzado el límite de 5 API Keys por hora desde tu dirección IP. Intenta más tarde o contáctanos si requieres cuota adicional.";
            };
            log.warn("Public key registration rate limit triggered ({}) for IP: {}", limitStatus, clientIp);
            renderKeyError(ctx, baseUrl, errorMsg);
            return;
        }

        // 3. Strict Input Validation on Client Name
        String rawName = ctx.formParam("clientName");
        if (rawName == null || rawName.trim().length() < 2 || rawName.trim().length() > 40) {
            renderKeyError(ctx, baseUrl, "Por favor ingresa un nombre válido para tu aplicación (entre 2 y 40 caracteres).");
            return;
        }

        String sanitizedName = rawName.trim();
        if (!VALID_NAME_PATTERN.matcher(sanitizedName).matches()) {
            renderKeyError(ctx, baseUrl, "El nombre solo puede contener letras, números, espacios, guiones o puntos.");
            return;
        }

        // 4. Safe Creation (Password hashing is strictly internal; raw key only returned once)
        ClientCreationResult creationResult = apiKeyService.createClient(sanitizedName, "CONSUMER", PUBLIC_TIER_RPM);

        // Log sanitization to prevent log injection
        String logSafeName = sanitizedName.replaceAll("[\\r\\n\\t]", "_");
        log.info("Self-service API Key created for '{}' from IP: {}", logSafeName, clientIp);

        Map<String, Object> model = new HashMap<>();
        model.put("success", true);
        model.put("errorMessage", null);
        model.put("creationResult", creationResult);
        model.put("baseUrl", baseUrl);
        model.put("publicBasePath", config.publicBasePath());
        model.put("serverPort", config.serverPort());
        ctx.render("fragments/public-key-result.html", model);
    }

    private void renderKeyError(Context ctx, String baseUrl, String errorMessage) {
        Map<String, Object> model = new HashMap<>();
        model.put("success", false);
        model.put("errorMessage", errorMessage);
        model.put("creationResult", null);
        model.put("baseUrl", baseUrl);
        model.put("publicBasePath", config.publicBasePath());
        ctx.render("fragments/public-key-result.html", model);
    }

    public synchronized LimitStatus tryAcquireSlot(String ip) {
        Instant now = Instant.now();
        Instant cutoff = now.minus(IP_WINDOW);

        // 1. Global limit check
        while (!globalRegistrationHistory.isEmpty() && globalRegistrationHistory.peekFirst().isBefore(cutoff)) {
            globalRegistrationHistory.pollFirst();
        }
        if (globalRegistrationHistory.size() >= GLOBAL_MAX_KEYS_PER_HOUR) {
            return LimitStatus.GLOBAL_LIMIT_EXCEEDED;
        }

        // 2. Per-IP limit and burst cooldown check
        Deque<Instant> deque = ipRegistrationHistory.computeIfAbsent(ip, k -> new ArrayDeque<>());
        while (!deque.isEmpty() && deque.peekFirst().isBefore(cutoff)) {
            deque.pollFirst();
        }

        if (deque.size() >= MAX_KEYS_PER_IP_PER_HOUR) {
            return LimitStatus.IP_LIMIT_EXCEEDED;
        }

        if (!deque.isEmpty()) {
            Instant lastRequest = deque.peekLast();
            if (Duration.between(lastRequest, now).compareTo(IP_BURST_COOLDOWN) < 0) {
                return LimitStatus.COOLDOWN_ACTIVE;
            }
        }

        deque.addLast(now);
        globalRegistrationHistory.addLast(now);

        // Memory cleanup
        if (ipRegistrationHistory.size() > 1000) {
            ipRegistrationHistory.entrySet().removeIf(entry -> {
                Deque<Instant> d = entry.getValue();
                return d.isEmpty() || d.peekLast().isBefore(cutoff);
            });
        }

        return LimitStatus.ALLOWED;
    }

    public synchronized boolean tryAcquireIpSlot(String ip) {
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
        return true;
    }

    private boolean isOriginAllowed(Context ctx) {
        String origin = ctx.header("Origin");
        if (origin == null || origin.isBlank()) {
            origin = ctx.header("Referer");
        }
        if (origin == null || origin.isBlank()) {
            return true;
        }
        try {
            URI uri = URI.create(origin);
            String originHost = uri.getHost();
            if (originHost == null) return false;

            String expectedHost = ctx.header("X-Forwarded-Host");
            if (expectedHost == null || expectedHost.isBlank()) {
                expectedHost = ctx.header("Host");
            }
            if (expectedHost != null && expectedHost.contains(":")) {
                expectedHost = expectedHost.split(":")[0];
            }

            return originHost.equalsIgnoreCase(expectedHost) ||
                    originHost.equalsIgnoreCase("localhost") ||
                    originHost.equalsIgnoreCase("127.0.0.1");
        } catch (Exception e) {
            return false;
        }
    }

    private String resolveClientIp(Context ctx) {
        String xForwardedFor = ctx.header("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            String[] parts = xForwardedFor.split(",");
            // The last IP in X-Forwarded-For is the one appended by the nearest trusted reverse proxy (Caddy)
            return parts[parts.length - 1].trim();
        }
        String realIp = ctx.header("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return ctx.ip();
    }

    private String resolveBaseUrl(Context ctx) {
        String host = ctx.header("X-Forwarded-Host");
        if (host == null || host.isBlank()) {
            host = ctx.header("Host");
        }
        if (host == null || host.isBlank()) {
            host = "155.181.158.36:" + config.serverPort();
        }
        String scheme = ctx.header("X-Forwarded-Proto");
        if (scheme == null || scheme.isBlank()) {
            scheme = ctx.scheme();
        }
        if (scheme == null || scheme.isBlank()) {
            scheme = "http";
        }
        return scheme + "://" + host + config.publicBasePath();
    }
}

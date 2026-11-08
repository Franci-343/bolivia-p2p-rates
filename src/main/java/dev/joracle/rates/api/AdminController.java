package dev.joracle.rates.api;

import dev.joracle.rates.config.AppConfig;
import dev.joracle.rates.db.OracleDatabaseManager;
import dev.joracle.rates.model.ApiClient;
import dev.joracle.rates.model.ClientCreationResult;
import dev.joracle.rates.model.RateSnapshot;
import dev.joracle.rates.repository.OutboxRepository;
import dev.joracle.rates.repository.RateRepository;
import dev.joracle.rates.service.AdminSessionService;
import dev.joracle.rates.service.AdminSessionService.AdminSession;
import dev.joracle.rates.service.ApiKeyService;
import dev.joracle.rates.service.OracleSyncWorker;
import dev.joracle.rates.service.RatePollerService;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import io.javalin.http.Cookie;
import io.javalin.http.HttpStatus;
import io.javalin.http.SameSite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Controller handling the web-based Administrator Dashboard using Thymeleaf and HTMX.
 * Protected by opaque revocable sessions, secure cookies, Origin verification, and CSRF tokens.
 */
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);
    public static final String COOKIE_ADMIN_SESSION = "admin_session";
    private static final String COOKIE_LEGACY_ADMIN_KEY = "admin_key";

    private final RateRepository rateRepository;
    private final RatePollerService pollerService;
    private final ApiKeyService apiKeyService;
    private final AppConfig config;
    private final AdminSessionService sessionService;
    private final OracleDatabaseManager oracleDbManager;
    private final OracleSyncWorker oracleSyncWorker;
    private final OutboxRepository outboxRepository;

    public AdminController(
            RateRepository rateRepository,
            RatePollerService pollerService,
            ApiKeyService apiKeyService,
            AppConfig config
    ) {
        this(rateRepository, pollerService, apiKeyService, config, new AdminSessionService(), null, null, null);
    }

    public AdminController(
            RateRepository rateRepository,
            RatePollerService pollerService,
            ApiKeyService apiKeyService,
            AppConfig config,
            AdminSessionService sessionService
    ) {
        this(rateRepository, pollerService, apiKeyService, config, sessionService, null, null, null);
    }

    public AdminController(
            RateRepository rateRepository,
            RatePollerService pollerService,
            ApiKeyService apiKeyService,
            AppConfig config,
            AdminSessionService sessionService,
            OracleDatabaseManager oracleDbManager,
            OracleSyncWorker oracleSyncWorker,
            OutboxRepository outboxRepository
    ) {
        this.rateRepository = Objects.requireNonNull(rateRepository, "rateRepository cannot be null");
        this.pollerService = Objects.requireNonNull(pollerService, "pollerService cannot be null");
        this.apiKeyService = Objects.requireNonNull(apiKeyService, "apiKeyService cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.sessionService = Objects.requireNonNull(sessionService, "sessionService cannot be null");
        this.oracleDbManager = oracleDbManager;
        this.oracleSyncWorker = oracleSyncWorker;
        this.outboxRepository = outboxRepository;
    }

    public void registerRoutes(RoutesConfig routes) {
        routes.get("/admin", this::renderDashboard);
        routes.post("/admin/login", this::handleLogin);
        routes.post("/admin/logout", this::handleLogout);

        // HTMX Endpoints
        routes.get("/admin/partials/rates", this::renderRatesFragment);
        routes.post("/admin/refresh", this::handleRefreshRates);
        routes.post("/admin/clients", this::handleCreateClient);
        routes.post("/admin/clients/{id}/toggle", this::handleToggleClient);
        routes.delete("/admin/clients/{id}", this::handleDeleteClient);
        routes.get("/admin/partials/oracle-sync", this::renderOracleSyncFragment);
        routes.post("/admin/oracle-sync", this::handleManualOracleSync);
    }

    private void renderDashboard(Context ctx) {
        Optional<AdminAuthContext> authOpt = resolveAdminAuth(ctx);
        Map<String, Object> model = new HashMap<>();
        model.put("publicBasePath", config.publicBasePath());

        if (authOpt.isPresent()) {
            AdminAuthContext auth = authOpt.get();
            model.put("isAdminLoggedIn", true);
            model.put("adminName", auth.adminName());
            model.put("csrfToken", auth.session() != null ? auth.session().csrfToken() : "");
            populateDashboardModel(model, null, null);
        } else {
            model.put("isAdminLoggedIn", false);
            model.put("csrfToken", "");
        }

        ctx.render("admin.html", model);
    }

    private void handleLogin(Context ctx) {
        if (!isOriginAllowed(ctx)) {
            log.warn("Login rejected: Origin/Referer check failed from IP {}", resolveClientIp(ctx));
            renderLoginError(ctx, "Origen de solicitud no autorizado.");
            return;
        }

        String adminKey = ctx.formParam("adminKey");
        ApiKeyService.AuthResult auth = apiKeyService.authenticate(adminKey);

        if (auth.authorized() && auth.client().isAdmin()) {
            AdminSession session = sessionService.createSession(auth.client());
            String cookiePath = config.publicBasePath().isEmpty() ? "/" : config.publicBasePath();
            boolean isSecure = isConnectionSecure(ctx);

            Cookie cookie = new Cookie(COOKIE_ADMIN_SESSION, session.sessionId());
            cookie.setPath(cookiePath);
            cookie.setHttpOnly(true);
            cookie.setSecure(isSecure);
            cookie.setSameSite(SameSite.LAX);
            cookie.setMaxAge((int) AdminSessionService.SESSION_DURATION.toSeconds());
            ctx.cookie(cookie);

            // Clear legacy plain key cookie if present
            ctx.removeCookie(COOKIE_LEGACY_ADMIN_KEY, cookiePath);
            ctx.removeCookie(COOKIE_LEGACY_ADMIN_KEY, "/");

            log.info("Admin session created for '{}' from IP {} (Secure: {})",
                    auth.client().clientName(), resolveClientIp(ctx), isSecure);
            ctx.redirect(config.publicUrl("/admin"));
        } else {
            log.warn("Failed admin login attempt from IP {}", resolveClientIp(ctx));
            renderLoginError(ctx, "Clave de administrador inválida o sin privilegios de ADMIN.");
        }
    }

    private void handleLogout(Context ctx) {
        String sessionId = ctx.cookie(COOKIE_ADMIN_SESSION);
        if (sessionId != null) {
            sessionService.revokeSession(sessionId);
        }

        String cookiePath = config.publicBasePath().isEmpty() ? "/" : config.publicBasePath();
        ctx.removeCookie(COOKIE_ADMIN_SESSION, cookiePath);
        ctx.removeCookie(COOKIE_ADMIN_SESSION, "/admin");
        ctx.removeCookie(COOKIE_ADMIN_SESSION, "/");
        ctx.removeCookie(COOKIE_LEGACY_ADMIN_KEY, cookiePath);
        ctx.removeCookie(COOKIE_LEGACY_ADMIN_KEY, "/");

        ctx.redirect(config.publicUrl("/admin"));
    }

    private void renderRatesFragment(Context ctx) {
        Optional<AdminAuthContext> authOpt = resolveAdminAuth(ctx);
        if (authOpt.isEmpty()) {
            ctx.status(HttpStatus.UNAUTHORIZED).result("No autorizado");
            return;
        }

        Map<String, Object> model = new HashMap<>();
        RateSnapshot snapshot = rateRepository.findLatest().orElse(null);
        model.put("snapshot", snapshot);
        model.put("refreshMessage", null);
        model.put("publicBasePath", config.publicBasePath());
        model.put("csrfToken", authOpt.get().session() != null ? authOpt.get().session().csrfToken() : "");
        ctx.render("fragments/rates-card.html", model);
    }

    private void handleRefreshRates(Context ctx) {
        Optional<AdminAuthContext> authOpt = resolveAdminAuth(ctx);
        if (authOpt.isEmpty()) {
            ctx.status(HttpStatus.UNAUTHORIZED).result("No autorizado");
            return;
        }
        if (!validateCsrf(ctx, authOpt.get())) {
            ctx.status(HttpStatus.FORBIDDEN).result("Solicitud rechazada: verificación CSRF fallida");
            return;
        }

        RatePollerService.RefreshResult result = pollerService.refreshWithCooldown();
        Map<String, Object> model = new HashMap<>();
        model.put("snapshot", rateRepository.findLatest().orElse(null));
        model.put("refreshMessage", result.executed() ? "¡Mercado actualizado!" : "Cooldown activo (" + result.cooldownRemainingSeconds() + "s restantes)");
        model.put("publicBasePath", config.publicBasePath());
        model.put("csrfToken", authOpt.get().session() != null ? authOpt.get().session().csrfToken() : "");
        ctx.render("fragments/rates-card.html", model);
    }

    private void handleCreateClient(Context ctx) {
        Optional<AdminAuthContext> authOpt = resolveAdminAuth(ctx);
        if (authOpt.isEmpty()) {
            ctx.status(HttpStatus.UNAUTHORIZED).result("No autorizado");
            return;
        }
        if (!validateCsrf(ctx, authOpt.get())) {
            ctx.status(HttpStatus.FORBIDDEN).result("Solicitud rechazada: verificación CSRF fallida");
            return;
        }

        String clientName = ctx.formParam("clientName");
        String role = ctx.formParam("role");
        String rpmStr = ctx.formParam("rateLimitRpm");
        int rpm = 60;
        try {
            if (rpmStr != null) rpm = Integer.parseInt(rpmStr.trim());
        } catch (NumberFormatException ignored) {}

        ClientCreationResult creationResult = apiKeyService.createClient(clientName, role, rpm);

        Map<String, Object> model = new HashMap<>();
        model.put("clients", apiKeyService.listAllClients());
        model.put("newClientResult", creationResult);
        model.put("publicBasePath", config.publicBasePath());
        model.put("csrfToken", authOpt.get().session() != null ? authOpt.get().session().csrfToken() : "");
        ctx.render("fragments/clients-section.html", model);
    }

    private void handleToggleClient(Context ctx) {
        Optional<AdminAuthContext> authOpt = resolveAdminAuth(ctx);
        if (authOpt.isEmpty()) {
            ctx.status(HttpStatus.UNAUTHORIZED).result("No autorizado");
            return;
        }
        if (!validateCsrf(ctx, authOpt.get())) {
            ctx.status(HttpStatus.FORBIDDEN).result("Solicitud rechazada: verificación CSRF fallida");
            return;
        }

        long id = Long.parseLong(ctx.pathParam("id"));
        apiKeyService.toggleClientStatus(id);

        Map<String, Object> model = new HashMap<>();
        model.put("clients", apiKeyService.listAllClients());
        model.put("newClientResult", null);
        model.put("publicBasePath", config.publicBasePath());
        model.put("csrfToken", authOpt.get().session() != null ? authOpt.get().session().csrfToken() : "");
        ctx.render("fragments/clients-section.html", model);
    }

    private void handleDeleteClient(Context ctx) {
        Optional<AdminAuthContext> authOpt = resolveAdminAuth(ctx);
        if (authOpt.isEmpty()) {
            ctx.status(HttpStatus.UNAUTHORIZED).result("No autorizado");
            return;
        }
        if (!validateCsrf(ctx, authOpt.get())) {
            ctx.status(HttpStatus.FORBIDDEN).result("Solicitud rechazada: verificación CSRF fallida");
            return;
        }

        long id = Long.parseLong(ctx.pathParam("id"));
        apiKeyService.deleteClient(id);

        Map<String, Object> model = new HashMap<>();
        model.put("clients", apiKeyService.listAllClients());
        model.put("newClientResult", null);
        model.put("publicBasePath", config.publicBasePath());
        model.put("csrfToken", authOpt.get().session() != null ? authOpt.get().session().csrfToken() : "");
        ctx.render("fragments/clients-section.html", model);
    }

    private Optional<AdminAuthContext> resolveAdminAuth(Context ctx) {
        // 1. Session Cookie (opaque revocable session token)
        String sessionId = ctx.cookie(COOKIE_ADMIN_SESSION);
        if (sessionId != null && !sessionId.isBlank()) {
            Optional<AdminSession> sessionOpt = sessionService.getSession(sessionId);
            if (sessionOpt.isPresent()) {
                return Optional.of(new AdminAuthContext(sessionOpt.get().clientName(), sessionOpt.get(), null));
            }
        }

        // 2. Programmatic API Header X-API-Key (For automated scripts, not browser cookies)
        String headerKey = ctx.header("X-API-Key");
        if (headerKey != null && !headerKey.isBlank()) {
            ApiKeyService.AuthResult auth = apiKeyService.authenticate(headerKey);
            if (auth.authorized() && auth.client().isAdmin()) {
                return Optional.of(new AdminAuthContext(auth.client().clientName(), null, auth.client()));
            }
        }

        return Optional.empty();
    }

    private boolean validateCsrf(Context ctx, AdminAuthContext auth) {
        // 1. Origin / Referer validation
        if (!isOriginAllowed(ctx)) {
            log.warn("CSRF check failed: invalid Origin or Referer header from IP {}", resolveClientIp(ctx));
            return false;
        }

        // 2. Programmatic API key requests don't need CSRF tokens
        if (auth.apiClient() != null) {
            return true;
        }

        // 3. Browser session requests MUST provide valid CSRF Token
        if (auth.session() != null) {
            String token = ctx.header("X-CSRF-Token");
            if (token == null || token.isBlank()) {
                token = ctx.formParam("csrfToken");
            }
            if (token == null || !token.equals(auth.session().csrfToken())) {
                log.warn("CSRF token mismatch for admin session from IP {}", resolveClientIp(ctx));
                return false;
            }
        }

        return true;
    }

    private boolean isOriginAllowed(Context ctx) {
        String origin = ctx.header("Origin");
        if (origin == null || origin.isBlank()) {
            origin = ctx.header("Referer");
        }
        if (origin == null || origin.isBlank()) {
            // Allow if non-browser API client provided X-API-Key
            return ctx.header("X-API-Key") != null;
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
                    originHost.equalsIgnoreCase("155.181.158.36") ||
                    originHost.equalsIgnoreCase("localhost") ||
                    originHost.equalsIgnoreCase("127.0.0.1");
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isConnectionSecure(Context ctx) {
        String proto = ctx.header("X-Forwarded-Proto");
        if (proto != null && proto.toLowerCase().contains("https")) {
            return true;
        }
        if ("https".equalsIgnoreCase(ctx.scheme())) {
            return true;
        }
        // In production behind reverse proxy with public base path, force Secure=true unless explicit local dev
        String host = ctx.header("Host");
        if (host != null && (host.contains("localhost") || host.contains("127.0.0.1"))) {
            return false;
        }
        return !config.publicBasePath().isEmpty();
    }

    private String resolveClientIp(Context ctx) {
        String xForwardedFor = ctx.header("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            String[] parts = xForwardedFor.split(",");
            return parts[parts.length - 1].trim();
        }
        String realIp = ctx.header("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return ctx.ip();
    }

    private void renderLoginError(Context ctx, String errorMsg) {
        Map<String, Object> model = new HashMap<>();
        model.put("isAdminLoggedIn", false);
        model.put("publicBasePath", config.publicBasePath());
        model.put("loginError", errorMsg);
        model.put("csrfToken", "");
        ctx.render("admin.html", model);
    }

    private void renderOracleSyncFragment(Context ctx) {
        Optional<AdminAuthContext> authOpt = resolveAdminAuth(ctx);
        if (authOpt.isEmpty()) {
            ctx.status(HttpStatus.UNAUTHORIZED).result("No autorizado");
            return;
        }

        Map<String, Object> model = new HashMap<>();
        populateOracleSyncModel(model, null);
        model.put("publicBasePath", config.publicBasePath());
        model.put("csrfToken", authOpt.get().session() != null ? authOpt.get().session().csrfToken() : "");
        ctx.render("fragments/oracle-sync-section.html", model);
    }

    private void handleManualOracleSync(Context ctx) {
        Optional<AdminAuthContext> authOpt = resolveAdminAuth(ctx);
        if (authOpt.isEmpty()) {
            ctx.status(HttpStatus.UNAUTHORIZED).result("No autorizado");
            return;
        }
        if (!validateCsrf(ctx, authOpt.get())) {
            ctx.status(HttpStatus.FORBIDDEN).result("Solicitud rechazada: verificación CSRF fallida");
            return;
        }

        String syncMessage = "Sincronización manual completada con éxito.";
        if (oracleSyncWorker != null) {
            int result = oracleSyncWorker.syncOnce();
            if (result < 0) {
                syncMessage = "Error al sincronizar: " + (oracleSyncWorker.getLastError() != null ? oracleSyncWorker.getLastError() : "Fallo de conexión");
            } else if (result == 0) {
                syncMessage = "No había eventos pendientes para sincronizar.";
            } else {
                syncMessage = "Se sincronizaron exitosamente " + result + " evento(s) hacia Oracle.";
            }
        } else {
            syncMessage = "La sincronización hacia Oracle no está configurada o se encuentra desactivada.";
        }

        Map<String, Object> model = new HashMap<>();
        populateOracleSyncModel(model, syncMessage);
        model.put("publicBasePath", config.publicBasePath());
        model.put("csrfToken", authOpt.get().session() != null ? authOpt.get().session().csrfToken() : "");
        ctx.render("fragments/oracle-sync-section.html", model);
    }

    private void populateOracleSyncModel(Map<String, Object> model, String syncMessage) {
        boolean enabled = oracleDbManager != null && oracleDbManager.isEnabled();
        long pending = outboxRepository != null ? outboxRepository.countPendingEvents() : 0;
        int activeConn = oracleDbManager != null ? oracleDbManager.getActiveConnections() : 0;
        int totalConn = oracleDbManager != null ? oracleDbManager.getTotalConnections() : 0;
        String lastSuccess = (oracleSyncWorker != null && oracleSyncWorker.getLastSuccessInstant() != null)
                ? oracleSyncWorker.getLastSuccessInstant().toString()
                : "Nunca";
        String lastError = oracleSyncWorker != null ? oracleSyncWorker.getLastError() : null;
        long totalSynced = oracleSyncWorker != null ? oracleSyncWorker.getTotalEventsSynced() : 0;

        String syncStatus = "DISABLED";
        if (enabled) {
            if (lastError != null && oracleSyncWorker.getConsecutiveFailures() > 0) {
                syncStatus = "ERROR";
            } else if (pending > 0) {
                syncStatus = "PENDING";
            } else {
                syncStatus = "SYNCED";
            }
        }

        model.put("oracleEnabled", enabled);
        model.put("oracleSyncStatus", syncStatus);
        model.put("oraclePendingEvents", pending);
        model.put("oracleActiveConnections", activeConn);
        model.put("oracleTotalConnections", totalConn);
        model.put("oracleLastSuccess", lastSuccess);
        model.put("oracleLastError", lastError);
        model.put("oracleTotalSynced", totalSynced);
        model.put("oracleSyncMessage", syncMessage);
    }

    private void populateDashboardModel(Map<String, Object> model, String refreshMsg, ClientCreationResult newKey) {
        RateSnapshot snapshot = rateRepository.findLatest().orElse(null);
        List<ApiClient> clients = apiKeyService.listAllClients();
        model.put("snapshot", snapshot);
        model.put("clients", clients);
        model.put("refreshMessage", refreshMsg);
        model.put("newClientResult", newKey);
        model.put("publicBasePath", config.publicBasePath());
        populateOracleSyncModel(model, null);
    }

    public record AdminAuthContext(
            String adminName,
            AdminSession session,
            ApiClient apiClient
    ) {}
}

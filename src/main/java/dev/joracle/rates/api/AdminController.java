package dev.joracle.rates.api;

import dev.joracle.rates.config.AppConfig;
import dev.joracle.rates.model.ApiClient;
import dev.joracle.rates.model.ClientCreationResult;
import dev.joracle.rates.model.RateSnapshot;
import dev.joracle.rates.repository.RateRepository;
import dev.joracle.rates.service.ApiKeyService;
import dev.joracle.rates.service.RatePollerService;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Controller handling the web-based Administrator Dashboard using Thymeleaf and HTMX.
 */
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);
    private static final String COOKIE_ADMIN_KEY = "admin_key";

    private final RateRepository rateRepository;
    private final RatePollerService pollerService;
    private final ApiKeyService apiKeyService;
    private final AppConfig config;

    public AdminController(
            RateRepository rateRepository,
            RatePollerService pollerService,
            ApiKeyService apiKeyService,
            AppConfig config
    ) {
        this.rateRepository = Objects.requireNonNull(rateRepository, "rateRepository cannot be null");
        this.pollerService = Objects.requireNonNull(pollerService, "pollerService cannot be null");
        this.apiKeyService = Objects.requireNonNull(apiKeyService, "apiKeyService cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
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
    }

    private void renderDashboard(Context ctx) {
        boolean authenticated = checkAdminAuth(ctx);
        Map<String, Object> model = new HashMap<>();
        model.put("isAdminLoggedIn", authenticated);

        if (authenticated) {
            populateDashboardModel(model, null, null);
        }

        ctx.render("admin.html", model);
    }

    private void handleLogin(Context ctx) {
        String adminKey = ctx.formParam("adminKey");
        ApiKeyService.AuthResult auth = apiKeyService.authenticate(adminKey);

        if (auth.authorized() && auth.client().isAdmin()) {
            ctx.cookie(COOKIE_ADMIN_KEY, adminKey.trim(), 60 * 60 * 24 * 7); // 7 days
            ctx.redirect("/admin");
        } else {
            Map<String, Object> model = new HashMap<>();
            model.put("isAdminLoggedIn", false);
            model.put("loginError", "Clave de administrador inválida o sin privilegios de ADMIN.");
            ctx.render("admin.html", model);
        }
    }

    private void handleLogout(Context ctx) {
        ctx.removeCookie(COOKIE_ADMIN_KEY, "/admin");
        ctx.removeCookie(COOKIE_ADMIN_KEY, "/");
        ctx.redirect("/admin");
    }

    private void renderRatesFragment(Context ctx) {
        if (!checkAdminAuth(ctx)) {
            ctx.status(HttpStatus.UNAUTHORIZED).result("No autorizado");
            return;
        }

        Map<String, Object> model = new HashMap<>();
        RateSnapshot snapshot = rateRepository.findLatest().orElse(null);
        model.put("snapshot", snapshot);
        model.put("refreshMessage", null);
        ctx.render("fragments/rates-card.html", model);
    }

    private void handleRefreshRates(Context ctx) {
        if (!checkAdminAuth(ctx)) {
            ctx.status(HttpStatus.UNAUTHORIZED).result("No autorizado");
            return;
        }

        RatePollerService.RefreshResult result = pollerService.refreshWithCooldown();
        Map<String, Object> model = new HashMap<>();
        model.put("snapshot", rateRepository.findLatest().orElse(null));
        model.put("refreshMessage", result.executed() ? "¡Mercado actualizado!" : "Cooldown activo (" + result.cooldownRemainingSeconds() + "s restantes)");
        ctx.render("fragments/rates-card.html", model);
    }

    private void handleCreateClient(Context ctx) {
        if (!checkAdminAuth(ctx)) {
            ctx.status(HttpStatus.UNAUTHORIZED).result("No autorizado");
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
        ctx.render("fragments/clients-section.html", model);
    }

    private void handleToggleClient(Context ctx) {
        if (!checkAdminAuth(ctx)) {
            ctx.status(HttpStatus.UNAUTHORIZED).result("No autorizado");
            return;
        }

        long id = Long.parseLong(ctx.pathParam("id"));
        apiKeyService.toggleClientStatus(id);

        Map<String, Object> model = new HashMap<>();
        model.put("clients", apiKeyService.listAllClients());
        model.put("newClientResult", null);
        ctx.render("fragments/clients-section.html", model);
    }

    private void handleDeleteClient(Context ctx) {
        if (!checkAdminAuth(ctx)) {
            ctx.status(HttpStatus.UNAUTHORIZED).result("No autorizado");
            return;
        }

        long id = Long.parseLong(ctx.pathParam("id"));
        apiKeyService.deleteClient(id);

        Map<String, Object> model = new HashMap<>();
        model.put("clients", apiKeyService.listAllClients());
        model.put("newClientResult", null);
        ctx.render("fragments/clients-section.html", model);
    }

    private boolean checkAdminAuth(Context ctx) {
        // 1. Check query param ?key=... or ?adminKey=...
        String paramKey = ctx.queryParam("key");
        if (paramKey == null) paramKey = ctx.queryParam("adminKey");
        if (paramKey != null && !paramKey.isBlank()) {
            ApiKeyService.AuthResult auth = apiKeyService.authenticate(paramKey);
            if (auth.authorized() && auth.client().isAdmin()) {
                ctx.cookie(COOKIE_ADMIN_KEY, paramKey.trim(), 60 * 60 * 24 * 7);
                return true;
            }
        }

        // 2. Check cookie
        String cookieKey = ctx.cookie(COOKIE_ADMIN_KEY);
        if (cookieKey != null && !cookieKey.isBlank()) {
            ApiKeyService.AuthResult auth = apiKeyService.authenticate(cookieKey);
            if (auth.authorized() && auth.client().isAdmin()) {
                return true;
            }
        }

        // 3. Check header
        String headerKey = ctx.header("X-API-Key");
        if (headerKey != null && !headerKey.isBlank()) {
            ApiKeyService.AuthResult auth = apiKeyService.authenticate(headerKey);
            return auth.authorized() && auth.client().isAdmin();
        }

        return false;
    }

    private void populateDashboardModel(Map<String, Object> model, String refreshMsg, ClientCreationResult newKey) {
        RateSnapshot snapshot = rateRepository.findLatest().orElse(null);
        List<ApiClient> clients = apiKeyService.listAllClients();
        model.put("snapshot", snapshot);
        model.put("clients", clients);
        model.put("refreshMessage", refreshMsg);
        model.put("newClientResult", newKey);
    }
}

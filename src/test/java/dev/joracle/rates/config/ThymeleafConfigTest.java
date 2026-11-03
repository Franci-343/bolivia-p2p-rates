package dev.joracle.rates.config;

import dev.joracle.rates.model.ApiClient;
import dev.joracle.rates.model.ClientCreationResult;
import org.junit.jupiter.api.Test;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ThymeleafConfigTest {

    @Test
    void testRenderAdminTemplateWithoutSuffix() {
        TemplateEngine engine = ThymeleafConfig.createTemplateEngine();
        Context context = new Context();
        Map<String, Object> vars = new HashMap<>();
        vars.put("isAdminLoggedIn", false);
        context.setVariables(vars);

        assertDoesNotThrow(() -> {
            String rendered = engine.process("admin", context);
            assertNotNull(rendered);
            assertTrue(rendered.contains("Bolivia P2P"));
        });
    }

    @Test
    void testRenderAdminTemplateWithHtmlExtension() {
        TemplateEngine engine = ThymeleafConfig.createTemplateEngine();
        Context context = new Context();
        Map<String, Object> vars = new HashMap<>();
        vars.put("isAdminLoggedIn", true);
        vars.put("clients", List.of());
        vars.put("snapshot", null);
        vars.put("refreshMessage", "Prueba");
        context.setVariables(vars);

        assertDoesNotThrow(() -> {
            String rendered = engine.process("admin.html", context);
            assertNotNull(rendered);
            assertTrue(rendered.contains("Bolivia P2P"));
        });
    }

    @Test
    void testRenderRatesCardFragment() {
        TemplateEngine engine = ThymeleafConfig.createTemplateEngine();
        Context context = new Context();
        Map<String, Object> vars = new HashMap<>();
        vars.put("snapshot", null);
        vars.put("refreshMessage", "Sin datos disponibles todavía");
        context.setVariables(vars);

        assertDoesNotThrow(() -> {
            String rendered = engine.process("fragments/rates-card.html", context);
            assertNotNull(rendered);
            assertTrue(rendered.contains("Sin datos disponibles todavía"));
        });
    }

    @Test
    void testRenderClientsSectionFragment() {
        TemplateEngine engine = ThymeleafConfig.createTemplateEngine();
        Context context = new Context();
        ApiClient client = new ApiClient(1L, "client_test", "Test Client", "hash", "CONSUMER", 60, true, "2026-11-03 14:00:00");
        ClientCreationResult creation = new ClientCreationResult(client, "bolivia_live_secret123");

        Map<String, Object> vars = new HashMap<>();
        vars.put("clients", List.of(client));
        vars.put("newClientResult", creation);
        context.setVariables(vars);

        assertDoesNotThrow(() -> {
            String rendered = engine.process("fragments/clients-section.html", context);
            assertNotNull(rendered);
            assertTrue(rendered.contains("bolivia_live_secret123"));
            assertTrue(rendered.contains("Test Client"));
        });
    }
}

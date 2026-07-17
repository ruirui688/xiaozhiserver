package xiaozhi.modules.userplugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;

import org.junit.jupiter.api.Test;

import xiaozhi.common.exception.RenException;

class SharedPluginConfigRegistryTest {

    @Test
    void stripsSharedSecretsAndDefaultsFromAgentParameters() {
        Map<String, Object> result = SharedPluginConfigRegistry.stripSharedAgentFields(
                "SYSTEM_PLUGIN_WEB_SEARCH",
                Map.of(
                        "provider", "metaso",
                        "api_key", "secret",
                        "description", "agent-specific",
                        "max_results", "8"));

        assertFalse(result.containsKey("provider"));
        assertFalse(result.containsKey("api_key"));
        assertEquals("agent-specific", result.get("description"));
        assertEquals("8", result.get("max_results"));
    }

    @Test
    void validatesWeatherHostWithoutAllowingSuffixLookalikes() {
        SharedPluginConfigRegistry.Definition definition =
                SharedPluginConfigRegistry.requireByProviderCode("get_weather");

        Map<String, Object> valid = SharedPluginConfigRegistry.normalize(
                definition,
                Map.of(
                        "api_host", "abc123.qweatherapi.com",
                        "api_key", "secret",
                        "default_location", "广州"),
                Map.of());

        assertEquals("abc123.qweatherapi.com", valid.get("api_host"));
        assertThrows(RenException.class, () -> SharedPluginConfigRegistry.normalize(
                definition,
                Map.of(
                        "api_host", "qweatherapi.com.attacker.example",
                        "api_key", "secret",
                        "default_location", "广州"),
                Map.of()));
    }
}

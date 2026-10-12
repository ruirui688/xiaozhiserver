package xiaozhi.modules.model.support;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import xiaozhi.modules.model.dto.ModelProviderDTO;

class PluginCatalogContractTest {
    @Test
    void retiredToolsCannotClaimRuntimeAvailabilityEvenWithHistoricalFlags() {
        for (String code : List.of("set_session_voice", "manage_memory")) {
            ModelProviderDTO provider = provider(code,
                    "[{\"key\":\"runtime_builtin\",\"default\":true}]");
            PluginCatalogContract.decorate(provider);
            assertEquals("retired", provider.getCatalogMetadata().get("lifecycle"));
            assertEquals(false, provider.getCatalogMetadata().get("selectable"));
            assertEquals("unavailable", provider.getCatalogMetadata().get("activationMode"));
            assertEquals(List.of(), provider.getCatalogMetadata().get("modelPaths"));
            assertEquals(false, PluginCatalogContract.defaults(provider.getFields()).get("runtime_builtin"));
        }
    }

    @Test
    void builtinAndCustomActivitiesShareAnExplicitStaticSupportContract() {
        ModelProviderDTO builtin = provider("idiom_chain", "[]");
        ModelProviderDTO custom = provider("my_story", "[{\"key\":\"custom_plugin\",\"default\":true}]");
        for (ModelProviderDTO provider : List.of(builtin, custom)) {
            PluginCatalogContract.decorate(provider);
            assertEquals("activity", provider.getCatalogMetadata().get("capabilityType"));
            assertEquals("on_demand_activity", provider.getCatalogMetadata().get("injectionMode"));
            assertEquals("not_evaluated", provider.getCatalogMetadata().get("runtimeAvailability"));
            assertEquals(List.of("function_call", "intent_llm", "qwen_realtime", "doubao_duplex"),
                    provider.getCatalogMetadata().get("modelPaths"));
        }
    }

    @Test
    void catalogUpdatesReachInheritedRulesAndPreserveLegacyAndExplicitEmptyOverrides() {
        String oldCatalog = "[{\"key\":\"instructions\",\"default\":\"old rules\"}]";
        String nextCatalog = "[{\"key\":\"instructions\",\"default\":\"new rules\"},"
                + "{\"key\":\"opening_message\",\"default\":\"new opening\"}]";
        assertEquals("old rules", PluginCatalogContract.runtimeParams(oldCatalog, Map.of()).get("instructions"));
        assertEquals("new rules", PluginCatalogContract.runtimeParams(nextCatalog, Map.of()).get("instructions"));
        assertEquals("custom rules", PluginCatalogContract.runtimeParams(nextCatalog,
                Map.of("instructions", "custom rules")).get("instructions"));
        assertEquals("", PluginCatalogContract.runtimeParams(nextCatalog,
                Map.of("instructions", "", "opening_message", "")).get("opening_message"));
        // Historical values are never guessed to be inherited, even if equal to the old default.
        assertEquals("old rules", PluginCatalogContract.runtimeParams(nextCatalog,
                Map.of("instructions", "old rules")).get("instructions"));
    }

    @Test
    void roleCannotOverrideCatalogMetadataOrRetainASecondToolDescription() {
        Map<String, Object> result = PluginCatalogContract.runtimeParams(
                "[{\"key\":\"custom_plugin\",\"default\":true},"
                        + "{\"key\":\"parameters_schema\",\"default\":\"trusted schema\"}]",
                Map.of("description", "old role description", "custom_plugin", false,
                        "parameters_schema", "forged schema", "max_results", 4));
        assertFalse(result.containsKey("description"));
        assertEquals(true, result.get("custom_plugin"));
        assertEquals("trusted schema", result.get("parameters_schema"));
        assertEquals(4, result.get("max_results"));
        assertEquals(Map.of("instructions", "keep"), PluginCatalogContract.roleParams(
                Map.of("description", "old", "runtime_builtin", true, "instructions", "keep")));
    }

    @Test
    void legacyDescriptionOnlyActivityPreservesRulesOnlyWhenNeitherLayerDefinesInstructions() {
        Map<String, Object> legacy = Map.of("custom_plugin", true, "description", "legacy activity rules");
        assertEquals("legacy activity rules", PluginCatalogContract.roleParams(legacy, "[]").get("instructions"));
        String catalog = "[{\"key\":\"instructions\",\"default\":\"catalog rules\"}]";
        assertFalse(PluginCatalogContract.roleParams(legacy, catalog).containsKey("instructions"));
        assertEquals("catalog rules", PluginCatalogContract.runtimeParams(catalog, legacy).get("instructions"));
        String explicitEmpty = "[{\"key\":\"instructions\",\"default\":\"\"}]";
        assertEquals("", PluginCatalogContract.runtimeParams(explicitEmpty, legacy).get("instructions"));
        assertEquals("", PluginCatalogContract.runtimeParams("[]",
                Map.of("custom_plugin", true, "description", "legacy", "instructions", "")).get("instructions"));
        assertFalse(PluginCatalogContract.roleParams(Map.of("description", "tool trigger"), "[]")
                .containsKey("instructions"));
    }

    private ModelProviderDTO provider(String code, String fields) {
        ModelProviderDTO provider = new ModelProviderDTO();
        provider.setModelType("Plugin");
        provider.setProviderCode(code);
        provider.setFields(fields);
        return provider;
    }
}

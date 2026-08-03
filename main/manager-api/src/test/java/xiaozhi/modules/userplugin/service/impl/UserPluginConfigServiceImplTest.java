package xiaozhi.modules.userplugin.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.core.type.TypeReference;

import xiaozhi.common.security.CredentialCipher;
import xiaozhi.common.utils.JsonUtils;
import xiaozhi.modules.agent.dao.AgentPluginMappingMapper;
import xiaozhi.modules.userplugin.dao.UserPluginConfigDao;
import xiaozhi.modules.userplugin.dto.UserPluginConfigSaveDTO;
import xiaozhi.modules.userplugin.entity.UserPluginConfigEntity;
import xiaozhi.modules.userplugin.vo.UserPluginConfigVO;

class UserPluginConfigServiceImplTest {
    private UserPluginConfigDao configDao;
    private AgentPluginMappingMapper mappingMapper;
    private CredentialCipher cipher;
    private UserPluginConfigServiceImpl service;

    @BeforeEach
    void setUp() {
        configDao = mock(UserPluginConfigDao.class);
        mappingMapper = mock(AgentPluginMappingMapper.class);
        cipher = mock(CredentialCipher.class);
        service = new UserPluginConfigServiceImpl(
                configDao, mappingMapper, cipher, mock(ApplicationEventPublisher.class));
        when(mappingMapper.selectUserPluginMappings(any(), any())).thenReturn(List.of());
    }

    @Test
    void savesEncryptedWeatherConfigAndNeverStoresPlaintextInEntity() {
        when(configDao.selectOne(any())).thenReturn(null);
        when(cipher.encrypt(any(), any())).thenReturn("v1:encrypted");
        UserPluginConfigSaveDTO dto = new UserPluginConfigSaveDTO();
        dto.setConfig(Map.of(
                "api_host", "tenant.qweatherapi.com",
                "api_key", "weather-secret",
                "default_location", "深圳"));

        service.saveConfig(7L, "get_weather", dto);

        ArgumentCaptor<UserPluginConfigEntity> entityCaptor =
                ArgumentCaptor.forClass(UserPluginConfigEntity.class);
        verify(configDao).insert(entityCaptor.capture());
        assertEquals("v1:encrypted", entityCaptor.getValue().getConfigCiphertext());
        ArgumentCaptor<String> plaintextCaptor = ArgumentCaptor.forClass(String.class);
        verify(cipher).encrypt(eq("user-plugin:7:get_weather"), plaintextCaptor.capture());
        Map<String, Object> plaintext = JsonUtils.parseObject(
                plaintextCaptor.getValue(), new TypeReference<Map<String, Object>>() {
                });
        assertEquals("weather-secret", plaintext.get("api_key"));
        assertEquals("tenant.qweatherapi.com", plaintext.get("api_host"));
        assertEquals("深圳", plaintext.get("default_location"));
    }

    @Test
    void responseDoesNotExposeSecretAndBlankUpdatePreservesIt() {
        UserPluginConfigEntity entity = entity("web_search", "v1:cipher");
        when(configDao.selectOne(any())).thenReturn(entity);
        when(cipher.decrypt("user-plugin:7:web_search", "v1:cipher"))
                .thenReturn("{\"provider\":\"metaso\",\"api_key\":\"search-secret\"}");
        when(cipher.encrypt(any(), any())).thenReturn("v1:updated");

        UserPluginConfigVO response = service.getConfig(7L, "web_search");
        assertEquals(Map.of("provider", "metaso"), response.getConfig());
        assertFalse(response.getConfig().containsKey("api_key"));

        UserPluginConfigSaveDTO dto = new UserPluginConfigSaveDTO();
        dto.setConfig(Map.of("provider", "tavily", "api_key", ""));
        service.saveConfig(7L, "web_search", dto);

        ArgumentCaptor<String> plaintextCaptor = ArgumentCaptor.forClass(String.class);
        verify(cipher).encrypt(eq("user-plugin:7:web_search"), plaintextCaptor.capture());
        Map<String, Object> plaintext = JsonUtils.parseObject(
                plaintextCaptor.getValue(), new TypeReference<Map<String, Object>>() {
                });
        assertEquals("search-secret", plaintext.get("api_key"));
        assertEquals("tavily", plaintext.get("provider"));
    }

    @Test
    void runtimeMergeUsesUserCredentialsButKeepsAgentBehaviorSettings() {
        UserPluginConfigEntity entity = entity("web_search", "v1:cipher");
        when(configDao.selectOne(any())).thenReturn(entity);
        when(cipher.decrypt("user-plugin:7:web_search", "v1:cipher"))
                .thenReturn("{\"provider\":\"tavily\",\"api_key\":\"shared-secret\"}");

        String mergedJson = service.mergeRuntimeConfig(
                7L,
                "web_search",
                "{\"provider\":\"metaso\",\"api_key\":\"legacy\",\"max_results\":\"9\"}");
        Map<String, Object> merged = JsonUtils.parseObject(
                mergedJson, new TypeReference<Map<String, Object>>() {
                });

        assertEquals("tavily", merged.get("provider"));
        assertEquals("shared-secret", merged.get("api_key"));
        assertEquals("9", merged.get("max_results"));
    }

    @Test
    void unsupportedPluginKeepsAgentRuntimeConfigWithoutDatabaseLookup() {
        String value = "{\"enabled\":true}";
        assertEquals(value, service.mergeRuntimeConfig(7L, "other_plugin", value));
        verify(configDao, never()).selectOne(any());
    }

    private UserPluginConfigEntity entity(String providerCode, String ciphertext) {
        UserPluginConfigEntity entity = new UserPluginConfigEntity();
        entity.setId(1L);
        entity.setUserId(7L);
        entity.setProviderCode(providerCode);
        entity.setConfigCiphertext(ciphertext);
        return entity;
    }
}

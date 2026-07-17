package xiaozhi.modules.userplugin.service.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;

import lombok.RequiredArgsConstructor;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.security.CredentialCipher;
import xiaozhi.common.utils.JsonUtils;
import xiaozhi.modules.agent.dao.AgentPluginMappingMapper;
import xiaozhi.modules.agent.entity.AgentPluginMapping;
import xiaozhi.modules.userplugin.SharedPluginConfigRegistry;
import xiaozhi.modules.userplugin.SharedPluginConfigRegistry.Definition;
import xiaozhi.modules.userplugin.dao.UserPluginConfigDao;
import xiaozhi.modules.userplugin.dto.UserPluginConfigSaveDTO;
import xiaozhi.modules.userplugin.entity.UserPluginConfigEntity;
import xiaozhi.modules.userplugin.service.UserPluginConfigService;
import xiaozhi.modules.userplugin.vo.UserPluginConfigVO;

@Service
@RequiredArgsConstructor
public class UserPluginConfigServiceImpl implements UserPluginConfigService {
    private static final String CIPHER_SCOPE_PREFIX = "user-plugin:";

    private final UserPluginConfigDao configDao;
    private final AgentPluginMappingMapper agentPluginMappingMapper;
    private final CredentialCipher credentialCipher;

    @Override
    public List<UserPluginConfigVO> listConfigs(Long userId) {
        List<UserPluginConfigVO> result = new ArrayList<>();
        result.add(getConfig(userId, SharedPluginConfigRegistry.WEATHER_PROVIDER));
        result.add(getConfig(userId, SharedPluginConfigRegistry.WEB_SEARCH_PROVIDER));
        return result;
    }

    @Override
    public UserPluginConfigVO getConfig(Long userId, String providerCode) {
        Definition definition = SharedPluginConfigRegistry.requireByProviderCode(providerCode);
        UserPluginConfigEntity entity = find(userId, definition.providerCode());
        Map<String, Object> config = entity == null ? Map.of() : decrypt(entity);
        return UserPluginConfigVO.builder()
                .providerCode(definition.providerCode())
                .pluginName(definition.pluginName())
                .configured(entity != null)
                .secretConfigured(entity != null && SharedPluginConfigRegistry.secretsConfigured(definition, config))
                .config(SharedPluginConfigRegistry.publicConfig(definition, config))
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveConfig(Long userId, String providerCode, UserPluginConfigSaveDTO dto) {
        requireUser(userId);
        Definition definition = SharedPluginConfigRegistry.requireByProviderCode(providerCode);
        UserPluginConfigEntity entity = find(userId, definition.providerCode());
        Map<String, Object> existing = entity == null ? Map.of() : decrypt(entity);
        Map<String, Object> normalized = SharedPluginConfigRegistry.normalize(
                definition, dto.getConfig(), existing);

        boolean creating = entity == null;
        if (creating) {
            entity = new UserPluginConfigEntity();
            entity.setUserId(userId);
            entity.setProviderCode(definition.providerCode());
            entity.setCreator(userId);
        }
        entity.setConfigCiphertext(credentialCipher.encrypt(
                scope(userId, definition.providerCode()),
                JsonUtils.toJsonString(normalized)));
        entity.setUpdater(userId);
        if (creating) {
            configDao.insert(entity);
        } else {
            configDao.updateById(entity);
        }
        removeLegacyAgentFields(userId, definition);
    }

    @Override
    public void deleteConfig(Long userId, String providerCode) {
        requireUser(userId);
        Definition definition = SharedPluginConfigRegistry.requireByProviderCode(providerCode);
        configDao.delete(new LambdaQueryWrapper<UserPluginConfigEntity>()
                .eq(UserPluginConfigEntity::getUserId, userId)
                .eq(UserPluginConfigEntity::getProviderCode, definition.providerCode()));
    }

    @Override
    public String mergeRuntimeConfig(Long userId, String providerCode, String agentParamInfo) {
        Definition definition = SharedPluginConfigRegistry.findByProviderCode(providerCode);
        if (definition == null || userId == null) {
            return agentParamInfo;
        }
        UserPluginConfigEntity entity = find(userId, providerCode);
        if (entity == null) {
            return agentParamInfo;
        }
        Map<String, Object> agentConfig = parseConfig(agentParamInfo);
        definition.sharedKeys().forEach(agentConfig::remove);
        Map<String, Object> merged = new LinkedHashMap<>(decrypt(entity));
        merged.putAll(agentConfig);
        return JsonUtils.toJsonString(merged);
    }

    private void removeLegacyAgentFields(Long userId, Definition definition) {
        List<AgentPluginMapping> mappings = agentPluginMappingMapper.selectUserPluginMappings(
                userId, definition.pluginId());
        for (AgentPluginMapping mapping : mappings) {
            Map<String, Object> params = parseConfig(mapping.getParamInfo());
            definition.sharedKeys().forEach(params::remove);
            mapping.setParamInfo(JsonUtils.toJsonString(params));
            agentPluginMappingMapper.updateById(mapping);
        }
    }

    private Map<String, Object> decrypt(UserPluginConfigEntity entity) {
        String plaintext = credentialCipher.decrypt(
                scope(entity.getUserId(), entity.getProviderCode()),
                entity.getConfigCiphertext());
        return parseConfig(plaintext);
    }

    private Map<String, Object> parseConfig(String value) {
        if (StringUtils.isBlank(value)) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> parsed = JsonUtils.parseObject(
                    value, new TypeReference<Map<String, Object>>() {
                    });
            return parsed == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parsed);
        } catch (RuntimeException e) {
            throw new RenException("插件配置JSON格式无效", e);
        }
    }

    private UserPluginConfigEntity find(Long userId, String providerCode) {
        requireUser(userId);
        return configDao.selectOne(new LambdaQueryWrapper<UserPluginConfigEntity>()
                .eq(UserPluginConfigEntity::getUserId, userId)
                .eq(UserPluginConfigEntity::getProviderCode, providerCode));
    }

    private void requireUser(Long userId) {
        if (userId == null) {
            throw new RenException("用户未登录");
        }
    }

    private String scope(Long userId, String providerCode) {
        return CIPHER_SCOPE_PREFIX + userId + ":" + providerCode;
    }
}

package xiaozhi.modules.userplugin.service;

import java.util.List;

import xiaozhi.modules.userplugin.dto.UserPluginConfigSaveDTO;
import xiaozhi.modules.userplugin.vo.UserPluginConfigVO;
import xiaozhi.modules.userplugin.vo.UserPluginTestVO;

public interface UserPluginConfigService {
    List<UserPluginConfigVO> listConfigs(Long userId);

    UserPluginConfigVO getConfig(Long userId, String providerCode);

    void saveConfig(Long userId, String providerCode, UserPluginConfigSaveDTO dto);

    void deleteConfig(Long userId, String providerCode);

    UserPluginTestVO testConfig(Long userId, String providerCode, UserPluginConfigSaveDTO dto);

    String mergeRuntimeConfig(Long userId, String providerCode, String agentParamInfo);
}

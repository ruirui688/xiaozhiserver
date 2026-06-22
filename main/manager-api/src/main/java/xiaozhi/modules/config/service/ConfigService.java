package xiaozhi.modules.config.service;

import java.util.List;
import java.util.Map;

public interface ConfigService {
    /**
     * 获取服务器配置
     *
     * @param isCache 是否缓存
     * @return 配置信息
     */
    Object getConfig(Boolean isCache);

    /**
     * 获取智能体模型配置
     *
     * @param macAddress     MAC地址
     * @param selectedModule 客户端已实例化的模型
     * @return 模型配置信息
     */
    Map<String, Object> getAgentModels(String macAddress, Map<String, String> selectedModule);

    /**
     * 按智能体ID构建运行时配置，用于后台评测等无设备连接场景。
     *
     * @param agentId 智能体ID
     * @return 智能体运行配置
     */
    Map<String, Object> getAgentRuntimeConfig(String agentId);

    /**
     * 获取智能体替换词
     *
     * @param macAddress 设备MAC地址
     * @return 替换词列表，格式如 ["模板1|模板01", "模板2|模板02"]
     */
    List<String> getCorrectWords(String macAddress);
}

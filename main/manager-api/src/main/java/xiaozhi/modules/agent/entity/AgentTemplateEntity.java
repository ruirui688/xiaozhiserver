package xiaozhi.modules.agent.entity;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;

import lombok.Data;
import xiaozhi.modules.agent.dto.AgentTemplateSnapshotDTO;

/**
 * 智能体配置模板表
 * 
 * @TableName ai_agent_template
 */
@TableName(value = "ai_agent_template", autoResultMap = true)
@Data
public class AgentTemplateEntity implements Serializable {
    /**
     * 智能体唯一标识
     */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 模板快照版本。 */
    private Integer version;

    /** NULL 表示系统模板，否则归属于指定工作空间。 */
    private String workspaceId;

    /** 与角色昵称分离的模板显示名称。 */
    private String templateName;

    /** 创建快照时的来源智能体，仅用于审计。 */
    private String sourceAgentId;

    /** 工作空间模板的完整安全快照；系统模板继续兼容原有列。 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private AgentTemplateSnapshotDTO snapshotJson;

    /**
     * 智能体编码
     */
    private String agentCode;

    /**
     * 智能体名称
     */
    private String agentName;

    /**
     * 语音识别模型标识
     */
    private String asrModelId;

    /** 当前生效的 ASR 热词表。 */
    private String asrHotwordId;

    /** 可选 ASR 热词表，模板仅保存引用。 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> asrHotwordIds;

    /**
     * 语音活动检测标识
     */
    private String vadModelId;

    /**
     * 大语言模型标识
     */
    private String llmModelId;

    /**
     * 小模型标识
     */
    private String slmModelId;

    /**
     * VLLM模型标识
     */
    private String vllmModelId;

    /**
     * 语音合成模型标识
     */
    private String ttsModelId;

    /**
     * 音色标识
     */
    private String ttsVoiceId;

    /**
     * 音色语言
     */
    private String ttsLanguage;

    /**
     * TTS音量
     */
    private Integer ttsVolume;

    /**
     * TTS语速
     */
    private Integer ttsRate;

    /**
     * TTS音调
     */
    private Integer ttsPitch;

    /**
     * 记忆模型标识
     */
    private String memModelId;

    /**
     * 意图模型标识
     */
    private String intentModelId;

    /**
     * 聊天记录配置（0不记录 1仅记录文本 2记录文本和语音）
     */
    private Integer chatHistoryConf;

    /**
     * 角色设定参数
     */
    private String systemPrompt;

    /**
     * 总结记忆
     */
    private String summaryMemory;
    /**
     * 语言编码
     */
    private String langCode;

    /**
     * 交互语种
     */
    private String language;

    /**
     * 排序权重
     */
    private Integer sort;

    /**
     * 创建者 ID
     */
    private Long creator;

    /**
     * 创建时间
     */
    private Date createdAt;

    /**
     * 更新者 ID
     */
    private Long updater;

    /**
     * 更新时间
     */
    private Date updatedAt;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}

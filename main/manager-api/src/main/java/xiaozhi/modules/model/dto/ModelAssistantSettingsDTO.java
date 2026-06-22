package xiaozhi.modules.model.dto;

import java.io.Serial;
import java.io.Serializable;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "模型配置助手设置")
public class ModelAssistantSettingsDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "助手使用的 LLM 模型配置 ID")
    private String llmModelId;

    @Schema(description = "助手使用的 LLM 模型名称")
    private String llmModelName;
}

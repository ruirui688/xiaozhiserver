package xiaozhi.modules.model.dto;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

import cn.hutool.json.JSONObject;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "模型配置助手响应")
public class ModelAssistantResponseDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "助手回答")
    private String answer;

    @Schema(description = "是否已有匹配适配器")
    private Boolean supported = false;

    @Schema(description = "匹配的适配器编码")
    private String matchedAdapter;

    @Schema(description = "匹配置信度 0-1")
    private Double confidence = 0D;

    @Schema(description = "可应用到表单的配置草案")
    private JSONObject draftConfigJson;

    @Schema(description = "缺失字段")
    private List<String> missingFields = List.of();

    @Schema(description = "风险或限制提醒")
    private List<String> warnings = List.of();

    @Schema(description = "新协议接入草案")
    private String protocolSpecDraft;

    @Schema(description = "接入实现任务清单")
    private List<String> implementationTasks = List.of();

    @Schema(description = "接入级自动化生成产物")
    private List<IntegrationArtifactDTO> integrationArtifacts = List.of();

    @Schema(description = "建议下一步")
    private List<String> nextActions = List.of();

    @Schema(description = "参与编排的智能体")
    private List<String> agents = List.of();

    @Schema(description = "原始模型响应是否成功解析为结构化 JSON")
    private Boolean structured = false;

    @Data
    @Schema(description = "模型适配器接入生成产物")
    public static class IntegrationArtifactDTO implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        @Schema(description = "建议文件路径")
        private String path;

        @Schema(description = "产物用途")
        private String purpose;

        @Schema(description = "内容类型或语言")
        private String language;

        @Schema(description = "生成内容")
        private String content;
    }
}

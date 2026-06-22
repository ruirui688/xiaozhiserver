package xiaozhi.modules.model.dto;

import java.io.Serial;
import java.io.Serializable;

import cn.hutool.json.JSONObject;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "模型配置助手请求")
public class ModelAssistantRequestDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "用户问题")
    private String message;

    @Schema(description = "模型类型")
    private String modelType;

    @Schema(description = "当前模型 ID")
    private String modelId;

    @Schema(description = "当前适配器编码")
    private String providerCode;

    @Schema(description = "当前配置 JSON")
    private JSONObject configJson;

    @Schema(description = "当前模型文档链接")
    private String docLink;

    @Schema(description = "用户粘贴的文档文本")
    private String documentText;

    @Schema(description = "用户粘贴的文档 URL")
    private String documentUrl;

    @Schema(description = "最近一次测试失败或测试详情")
    private String testMessage;

    @Schema(description = "页面上下文，例如 list/add/edit/test")
    private String pageContext;
}

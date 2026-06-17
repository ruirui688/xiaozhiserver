package xiaozhi.modules.agent.dto;

import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "可视化 MCP HTTP 工具配置")
public class McpNoCodeToolDTO {
    @Schema(description = "工具名称，暴露给大模型调用")
    private String name;

    @Schema(description = "工具描述")
    private String description;

    @Schema(description = "是否启用")
    private Boolean enabled;

    @Schema(description = "HTTP 方法")
    private String method;

    @Schema(description = "HTTP URL，可使用 {{参数名}} 占位")
    private String url;

    @Schema(description = "请求头，可使用 {{参数名}} 占位")
    private Map<String, String> headers;

    @Schema(description = "查询参数模板，可使用 {{参数名}} 占位")
    private Map<String, String> queryParams;

    @Schema(description = "JSON 请求体模板，可使用 {{参数名}} 占位")
    private String bodyTemplate;

    @Schema(description = "工具入参定义")
    private List<McpNoCodeToolParameterDTO> parameters;

    @Schema(description = "请求超时时间，秒")
    private Integer timeoutSeconds;
}

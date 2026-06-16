package xiaozhi.modules.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "可视化 MCP HTTP 工具参数")
public class McpNoCodeToolParameterDTO {
    @Schema(description = "参数名")
    private String name;

    @Schema(description = "参数类型：string/number/integer/boolean/object/array")
    private String type;

    @Schema(description = "参数描述")
    private String description;

    @Schema(description = "是否必填")
    private Boolean required;
}

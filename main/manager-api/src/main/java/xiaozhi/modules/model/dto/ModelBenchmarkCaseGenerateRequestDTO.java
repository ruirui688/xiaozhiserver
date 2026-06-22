package xiaozhi.modules.model.dto;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "模型评测用例生成请求")
public class ModelBenchmarkCaseGenerateRequestDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "模型类型")
    private String modelType;

    @Schema(description = "智能体ID")
    private String agentId;

    @Schema(description = "设备ID")
    private String deviceId;

    @Schema(description = "候选模型ID列表")
    private List<String> modelIds;

    @Schema(description = "期望问题数")
    private Integer limit;
}

package xiaozhi.modules.model.dto;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "模型评测用例生成响应")
public class ModelBenchmarkCaseGenerateResponseDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "用例来源")
    private String source;

    @Schema(description = "模型类型")
    private String modelType;

    @Schema(description = "生成模式")
    private String generationMode;

    @Schema(description = "是否调用外部模型")
    private Boolean externalModelUsed = false;

    @Schema(description = "智能体ID")
    private String agentId;

    @Schema(description = "智能体名称")
    private String agentName;

    @Schema(description = "用例问题")
    private List<String> questions = new ArrayList<>();

    @Schema(description = "可直接传给性能测试的多轮问题")
    private List<String> customQuestions = new ArrayList<>();

    @Schema(description = "候选用例详情")
    private List<ModelBenchmarkCaseCandidateDTO> cases = new ArrayList<>();

    @Schema(description = "生成说明")
    private String summary;
}

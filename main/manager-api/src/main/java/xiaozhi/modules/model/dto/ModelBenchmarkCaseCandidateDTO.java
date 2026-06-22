package xiaozhi.modules.model.dto;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "模型评测候选用例")
public class ModelBenchmarkCaseCandidateDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "用例ID")
    private String caseId;

    @Schema(description = "用例来源：preset/agent_profile/knowledge/plugin")
    private String source;

    @Schema(description = "测试场景")
    private String scenario;

    @Schema(description = "风险等级")
    private String riskLevel;

    @Schema(description = "标签")
    private List<String> tags;

    @Schema(description = "多轮用户输入；可直接作为性能测试自定义业务问题")
    private List<String> turns;

    @Schema(description = "期望行为")
    private String expectedBehavior;

    @Schema(description = "安全边界")
    private List<String> safetyRules;
}

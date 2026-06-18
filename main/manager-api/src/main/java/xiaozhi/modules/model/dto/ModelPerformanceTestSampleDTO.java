package xiaozhi.modules.model.dto;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "模型性能测试单轮样本")
public class ModelPerformanceTestSampleDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "轮次")
    private Integer iteration;

    @Schema(description = "是否成功")
    private Boolean success;

    @Schema(description = "响应耗时毫秒")
    private Long responseTimeMs;

    @Schema(description = "是否计入延时统计")
    private Boolean measured;

    @Schema(description = "测试级别: REAL_INFERENCE/CONNECTIVITY/CONFIG_ONLY/UNSUPPORTED")
    private String testLevel;

    @Schema(description = "测试结果说明")
    private String message;

    @Schema(description = "输出摘要")
    private String outputSummary;

    @Schema(description = "输入摘要")
    private String inputSummary;

    @Schema(description = "参考输出")
    private String referenceOutput;

    @Schema(description = "样本类型")
    private String sampleType;

    @Schema(description = "首 token 耗时毫秒")
    private Long ttftMs;

    @Schema(description = "首音频耗时毫秒")
    private Long firstAudioMs;

    @Schema(description = "输出速度 tokens/s")
    private Double tokensPerSecond;

    @Schema(description = "RTF")
    private Double rtf;

    @Schema(description = "CER")
    private Double cer;

    @Schema(description = "WER")
    private Double wer;

    @Schema(description = "质量评分")
    private Double qualityScore;

    @Schema(description = "错误类型")
    private String errorType;

    @Schema(description = "测试过程步骤")
    private List<String> processSteps;

    @Schema(description = "输入明细")
    private Map<String, Object> inputDetail;

    @Schema(description = "输出明细")
    private Map<String, Object> outputDetail;
}

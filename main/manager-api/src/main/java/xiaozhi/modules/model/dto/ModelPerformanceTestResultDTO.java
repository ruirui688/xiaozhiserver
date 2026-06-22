package xiaozhi.modules.model.dto;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "单个模型性能测试结果")
public class ModelPerformanceTestResultDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "模型ID")
    private String modelId;

    @Schema(description = "模型名称")
    private String modelName;

    @Schema(description = "模型类型")
    private String modelType;

    @Schema(description = "供应器类型")
    private String providerType;

    @Schema(description = "测试级别: REAL_INFERENCE/CONNECTIVITY/CONFIG_ONLY/UNSUPPORTED")
    private String testLevel;

    @Schema(description = "测试级别说明")
    private String testLevelText;

    @Schema(description = "是否有真实延时统计")
    private Boolean measured;

    @Schema(description = "请求轮数")
    private Integer iterations;

    @Schema(description = "成功次数")
    private Integer successCount;

    @Schema(description = "失败次数")
    private Integer failureCount;

    @Schema(description = "成功率")
    private Double successRate;

    @Schema(description = "最小响应耗时毫秒")
    private Long minLatencyMs;

    @Schema(description = "最大响应耗时毫秒")
    private Long maxLatencyMs;

    @Schema(description = "平均响应耗时毫秒")
    private Double avgLatencyMs;

    @Schema(description = "P50响应耗时毫秒")
    private Long p50LatencyMs;

    @Schema(description = "P90响应耗时毫秒")
    private Long p90LatencyMs;

    @Schema(description = "P95响应耗时毫秒")
    private Long p95LatencyMs;

    @Schema(description = "首 token 耗时毫秒")
    private Long ttftMs;

    @Schema(description = "输出速度 tokens/s")
    private Double tokensPerSecond;

    @Schema(description = "首音频耗时毫秒")
    private Long firstAudioMs;

    @Schema(description = "Real Time Factor")
    private Double rtf;

    @Schema(description = "字符错误率")
    private Double cer;

    @Schema(description = "词错误率")
    private Double wer;

    @Schema(description = "质量评分")
    private Double qualityScore;

    @Schema(description = "逻辑性评分")
    private Double logicScore;

    @Schema(description = "上下文稳定性评分")
    private Double contextScore;

    @Schema(description = "Judge模式: llm_judge/heuristic/混合")
    private String judgeMode;

    @Schema(description = "LLMJudge六维平均分")
    private Map<String, Object> dimensionScores;

    @Schema(description = "场景维度平均分")
    private Map<String, Object> scenarioScores;

    @Schema(description = "安全P0/P1失败数量")
    private Integer p0p1FailureCount;

    @Schema(description = "主对话模型推荐结论")
    private String recommendation;

    @Schema(description = "单轮样本")
    private List<ModelPerformanceTestSampleDTO> samples;

    @Schema(description = "摘要")
    private String summary;
}

package xiaozhi.modules.model.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@TableName("ai_model_benchmark_result")
@Schema(description = "模型专业评测结果")
public class ModelBenchmarkResultEntity {

    @TableId
    private String id;

    private String taskId;

    private String modelId;

    private String modelName;

    private String modelType;

    private String providerType;

    private String benchmarkLevel;

    private String benchmarkLevelText;

    private Integer successCount;

    private Integer failureCount;

    private Double successRate;

    private Double avgLatencyMs;

    private Long p50LatencyMs;

    private Long p90LatencyMs;

    private Long p95LatencyMs;

    private Double tokensPerSecond;

    private Long ttftMs;

    private Long firstAudioMs;

    private Double rtf;

    private Double cer;

    private Double wer;

    private Double qualityScore;

    private Double logicScore;

    private Double contextScore;

    private String summary;

    private String errorSummary;

    private Date createDate;
}

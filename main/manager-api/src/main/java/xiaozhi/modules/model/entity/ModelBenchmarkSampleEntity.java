package xiaozhi.modules.model.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@TableName("ai_model_benchmark_sample")
@Schema(description = "模型专业评测样本")
public class ModelBenchmarkSampleEntity {

    @TableId
    private String id;

    private String taskId;

    private String resultId;

    private String modelId;

    private Integer iteration;

    private String sampleType;

    private Integer success;

    private Integer measured;

    private Long latencyMs;

    private Long ttftMs;

    private Long firstAudioMs;

    private Double tokensPerSecond;

    private Double rtf;

    private Double cer;

    private Double wer;

    private Double qualityScore;

    private String inputSummary;

    private String outputSummary;

    private String referenceOutput;

    private String message;

    private String errorType;

    private String rawMetrics;

    private Date createDate;
}

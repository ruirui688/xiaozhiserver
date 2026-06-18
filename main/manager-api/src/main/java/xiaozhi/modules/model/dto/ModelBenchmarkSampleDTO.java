package xiaozhi.modules.model.dto;

import java.io.Serial;
import java.io.Serializable;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "模型专业评测样本")
public class ModelBenchmarkSampleDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Integer iteration;

    private String sampleType;

    private Boolean success;

    private Boolean measured;

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
}

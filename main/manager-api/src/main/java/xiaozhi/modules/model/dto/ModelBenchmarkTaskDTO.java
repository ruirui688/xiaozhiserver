package xiaozhi.modules.model.dto;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "模型专业评测任务")
public class ModelBenchmarkTaskDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;

    private String modelType;

    private List<String> modelIds;

    private Integer iterations;

    private Integer concurrency;

    private String status;

    private Integer progress;

    private String summary;

    private String errorMessage;

    private Date startedAt;

    private Date finishedAt;

    private Long durationMs;

    private Date createDate;

    private Date updateDate;

    private List<String> metricNotes;

    private List<ModelPerformanceTestResultDTO> results;
}

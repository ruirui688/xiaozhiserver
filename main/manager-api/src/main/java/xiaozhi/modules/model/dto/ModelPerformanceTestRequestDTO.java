package xiaozhi.modules.model.dto;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "模型性能测试请求")
public class ModelPerformanceTestRequestDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "模型类型")
    private String modelType;

    @Schema(description = "模型ID列表")
    private List<String> modelIds;

    @Schema(description = "每个模型测试轮数")
    private Integer iterations;

    @Schema(description = "并发模型数")
    private Integer concurrency;
}

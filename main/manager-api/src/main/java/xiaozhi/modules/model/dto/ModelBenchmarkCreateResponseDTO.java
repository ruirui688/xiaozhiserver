package xiaozhi.modules.model.dto;

import java.io.Serial;
import java.io.Serializable;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "模型专业评测创建响应")
public class ModelBenchmarkCreateResponseDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String taskId;

    private String status;
}

package xiaozhi.modules.model.dto;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "模型配置助手产物写入响应")
public class ModelAssistantApplyResponseDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "是否全部写入成功")
    private Boolean success = false;

    @Schema(description = "写入结果")
    private List<ArtifactWriteResultDTO> results = List.of();

    @Data
    @Schema(description = "单个产物写入结果")
    public static class ArtifactWriteResultDTO implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        @Schema(description = "文件路径")
        private String path;

        @Schema(description = "状态：written/skipped/failed")
        private String status;

        @Schema(description = "说明")
        private String message;
    }
}

package xiaozhi.modules.model.dto;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import xiaozhi.modules.model.dto.ModelAssistantResponseDTO.IntegrationArtifactDTO;

@Data
@Schema(description = "模型配置助手产物写入请求")
public class ModelAssistantApplyRequestDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "要写入工作区的生成产物")
    private List<IntegrationArtifactDTO> artifacts = List.of();

    @Schema(description = "是否覆盖已有文件")
    private Boolean overwrite = false;
}

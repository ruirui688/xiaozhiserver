package xiaozhi.modules.memory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "长期记忆重建请求")
public class MemoryRebuildRequestDTO {
    @Schema(description = "最近会话数量，默认 15，最大 50")
    private Integer sessionLimit;

    @Schema(description = "重建模式：merge 或 clear_then_rebuild")
    private String mode;

    @Schema(description = "是否只预览，不写入数据库")
    private Boolean dryRun;
    private String deviceId;
}

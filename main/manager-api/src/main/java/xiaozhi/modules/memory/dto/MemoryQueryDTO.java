package xiaozhi.modules.memory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "服务端记忆查询")
public class MemoryQueryDTO {
    private String macAddress;
    private String agentId;
    private String query;
    private Integer limit;
}

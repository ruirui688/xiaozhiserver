package xiaozhi.modules.memory.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "会话结束后的主动长期记忆处理请求")
public class MemorySessionProcessDTO {
    @NotBlank
    @Size(max = 64)
    private String agentId;

    @Size(max = 64)
    private String macAddress;

    @NotBlank
    @Size(max = 64)
    private String sessionId;

    @Size(max = 64)
    private String segmentId;

    @Valid
    @NotEmpty
    @Size(max = 200)
    private List<MemorySessionMessageDTO> messages;
}

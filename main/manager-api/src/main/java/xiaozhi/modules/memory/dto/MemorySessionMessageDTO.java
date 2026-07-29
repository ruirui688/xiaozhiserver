package xiaozhi.modules.memory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "会话结束时提交给主动记忆模型的单条消息")
public class MemorySessionMessageDTO {
    @NotBlank
    @Size(max = 20)
    private String role;

    @NotBlank
    @Size(max = 8000)
    private String content;
}

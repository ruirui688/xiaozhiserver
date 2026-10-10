package xiaozhi.modules.memory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import com.fasterxml.jackson.annotation.JsonAlias;

@Data
@Schema(description = "会话结束时提交给主动记忆模型的单条消息")
public class MemorySessionMessageDTO {
    @JsonAlias({"id", "message_id"})
    @Size(max = 128)
    private String messageId;
    @JsonAlias("speaker_id")
    @Size(max = 128)
    private String speakerId;
    @JsonAlias("memory_eligible")
    private Boolean memoryEligible;
    @NotBlank
    @Size(max = 20)
    private String role;

    @NotBlank
    @Size(max = 8000)
    private String content;
}

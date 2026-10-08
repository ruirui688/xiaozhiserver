package xiaozhi.modules.hotword;
import java.util.List;
import jakarta.validation.constraints.*;
public record HotwordApplyDTO(@NotEmpty @Size(max = 100) List<@NotBlank @Size(max = 64) String> agentIds) {}

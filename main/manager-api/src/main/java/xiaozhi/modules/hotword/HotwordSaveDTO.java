package xiaozhi.modules.hotword;

import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public record HotwordSaveDTO(
        @NotBlank @Size(max = 80) String name,
        @NotBlank @Size(max = 64) String modelId,
        @NotEmpty @Size(max = 5000) List<@Valid Entry> entries) {
    public record Entry(@NotBlank @Size(max = 100) String word, @Min(1) @Max(10) Integer weight) {}
}

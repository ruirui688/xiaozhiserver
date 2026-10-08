package xiaozhi.modules.hotword;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record HotwordImportDTO(@NotBlank @Pattern(regexp = "[a-f0-9]{64}") String sourceId,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,128}") String remoteId) {}

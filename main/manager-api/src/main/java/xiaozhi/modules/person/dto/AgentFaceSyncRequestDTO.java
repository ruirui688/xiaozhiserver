package xiaozhi.modules.person.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AgentFaceSyncRequestDTO {
    @NotBlank
    @Size(max = 64)
    private String macAddress;

    @Size(max = 32)
    private String cachedAgentId;

    @Min(0)
    private Long releaseVersion;

    @Valid
    @Size(max = 100)
    private List<CachedProfile> profiles;

    @Data
    public static class CachedProfile {
        @NotBlank
        @Size(max = 32)
        private String personId;

        /**
         * Cached display metadata is part of the device inventory as well as
         * the immutable face asset.  Older devices omit these fields; a null
         * value then deliberately causes an upsert when the published
         * snapshot contains metadata.
         */
        @Size(max = 30)
        private String name;

        @Size(max = 60)
        private String groupName;

        @Size(max = 200)
        private String description;

        @Size(max = 32)
        private String recognitionAction;

        @Min(0)
        @Max(Long.MAX_VALUE)
        private Long faceVersion;

        @Size(max = 64)
        private String faceSha256;
    }
}

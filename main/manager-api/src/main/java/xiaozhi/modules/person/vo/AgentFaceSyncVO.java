package xiaozhi.modules.person.vo;

import java.util.List;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AgentFaceSyncVO {
    private int protocolVersion;
    private String agentId;
    private long releaseVersion;
    private boolean unchanged;
    private List<Profile> upserts;
    private List<String> deletes;

    @Data
    @Builder
    public static class Profile {
        private String personId;
        private String assetId;
        private String name;
        private String groupName;
        private String description;
        private long faceVersion;
        private String faceSha256;
        private String contentType;
        private String recognitionAction;
    }
}

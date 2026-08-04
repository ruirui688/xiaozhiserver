package xiaozhi.modules.person.vo;

import java.util.List;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DeviceFaceProfileManifestVO {
    private String deviceId;
    private String macAddress;
    private long syncVersion;
    private List<Profile> profiles;

    @Data
    @Builder
    public static class Profile {
        private String personId;
        private String name;
        private String groupName;
        private long faceVersion;
        private String faceSha256;
        private String contentType;
        private String recognitionAction;
    }
}

package xiaozhi.modules.person.service;

import xiaozhi.modules.person.dto.AgentFaceSyncRequestDTO;
import xiaozhi.modules.person.dto.DeviceFaceRecognitionEventDTO;
import xiaozhi.modules.person.vo.AgentFaceSyncVO;
import xiaozhi.modules.person.vo.DeviceFaceProfileManifestVO;
import xiaozhi.modules.person.vo.DeviceFaceRecognitionActionVO;

public interface PersonFaceRuntimeService {
    AgentFaceSyncVO sync(AgentFaceSyncRequestDTO request);

    PersonProfileService.FaceImage readPublishedFace(String macAddress, String personId, String assetId);

    DeviceFaceProfileManifestVO getLegacyManifest(String macAddress);

    PersonProfileService.FaceImage readLegacyFace(String macAddress, String personId);

    DeviceFaceRecognitionActionVO recordRecognition(DeviceFaceRecognitionEventDTO dto);
}

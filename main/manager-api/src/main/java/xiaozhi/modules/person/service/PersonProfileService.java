package xiaozhi.modules.person.service;

import java.util.List;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.web.multipart.MultipartFile;

import xiaozhi.modules.person.dto.DeviceFaceRecognitionEventDTO;
import xiaozhi.modules.person.dto.PersonProfileSaveDTO;
import xiaozhi.modules.person.vo.DeviceFaceProfileManifestVO;
import xiaozhi.modules.person.vo.DeviceFaceRecognitionActionVO;
import xiaozhi.modules.person.vo.PersonDeviceOptionVO;
import xiaozhi.modules.person.vo.PersonProfileVO;

public interface PersonProfileService {
    List<PersonProfileVO> list(String keyword);

    List<PersonDeviceOptionVO> listWorkspaceDevices();

    PersonProfileVO create(PersonProfileSaveDTO dto);

    PersonProfileVO update(String id, PersonProfileSaveDTO dto);

    void delete(String id);

    PersonProfileVO uploadFace(String id, MultipartFile file);

    void deleteFace(String id);

    FaceImage readFaceForManagement(String id);

    DeviceFaceProfileManifestVO getDeviceManifest(String macAddress);

    FaceImage readFaceForDevice(String macAddress, String personId);

    DeviceFaceRecognitionActionVO recordRecognition(DeviceFaceRecognitionEventDTO dto);

    record FaceImage(ByteArrayResource resource, String contentType, String sha256, long version) {
    }
}

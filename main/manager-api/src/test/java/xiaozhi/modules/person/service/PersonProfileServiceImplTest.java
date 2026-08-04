package xiaozhi.modules.person.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import xiaozhi.common.exception.RenException;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.person.dao.PersonDeviceBindingDao;
import xiaozhi.modules.person.dao.PersonProfileDao;
import xiaozhi.modules.person.dao.PersonRecognitionEventDao;
import xiaozhi.modules.person.dto.DeviceFaceRecognitionEventDTO;
import xiaozhi.modules.person.entity.PersonDeviceBindingEntity;
import xiaozhi.modules.person.entity.PersonProfileEntity;
import xiaozhi.modules.person.service.impl.PersonProfileServiceImpl;
import xiaozhi.modules.person.vo.DeviceFaceRecognitionActionVO;
import xiaozhi.modules.workspace.service.WorkspacePermissionService;

class PersonProfileServiceImplTest {
    @Test
    void deviceCanOnlyReadExplicitlyBoundFaceInSameWorkspace() {
        Fixture fixture = new Fixture();
        when(fixture.deviceDao.selectOne(any())).thenReturn(fixture.device());
        when(fixture.bindingDao.selectOne(any())).thenReturn(new PersonDeviceBindingEntity());
        when(fixture.profileDao.selectById("person-a")).thenReturn(fixture.profile("workspace-a"));
        when(fixture.storage.read("workspace-a/person-a/face.jpg")).thenReturn(new byte[] { 1, 2, 3 });

        PersonProfileService.FaceImage image = fixture.service.readFaceForDevice("AA:BB", "person-a");

        assertEquals(3, image.resource().contentLength());
        assertEquals("face-sha", image.sha256());
    }

    @Test
    void crossWorkspaceProfileIsRejectedEvenWithForgedPersonId() {
        Fixture fixture = new Fixture();
        when(fixture.deviceDao.selectOne(any())).thenReturn(fixture.device());
        when(fixture.bindingDao.selectOne(any())).thenReturn(new PersonDeviceBindingEntity());
        when(fixture.profileDao.selectById("person-a")).thenReturn(fixture.profile("workspace-b"));

        assertThrows(
                RenException.class,
                () -> fixture.service.readFaceForDevice("AA:BB", "person-a"));
    }

    @Test
    void recognitionEventReturnsConfiguredActionWithoutUploadingBiometrics() {
        Fixture fixture = new Fixture();
        PersonProfileEntity profile = fixture.profile("workspace-a");
        profile.setRecognitionAction("SPEAK_TEXT");
        profile.setGreetingText("欢迎回来");
        when(fixture.deviceDao.selectOne(any())).thenReturn(fixture.device());
        when(fixture.profileDao.selectById("person-a")).thenReturn(profile);
        when(fixture.bindingDao.selectCount(any())).thenReturn(1L);
        when(fixture.eventDao.selectOne(any())).thenReturn(null);

        DeviceFaceRecognitionEventDTO dto = new DeviceFaceRecognitionEventDTO();
        dto.setMacAddress("AA:BB");
        dto.setEventId("event-a");
        dto.setPersonId("person-a");
        dto.setConfidence(new BigDecimal("0.91"));
        DeviceFaceRecognitionActionVO action = fixture.service.recordRecognition(dto);

        assertEquals("SPEAK_TEXT", action.getActionType());
        assertEquals("欢迎回来", action.getText());
    }

    private static final class Fixture {
        final PersonProfileDao profileDao = mock(PersonProfileDao.class);
        final PersonDeviceBindingDao bindingDao = mock(PersonDeviceBindingDao.class);
        final PersonRecognitionEventDao eventDao = mock(PersonRecognitionEventDao.class);
        final DeviceDao deviceDao = mock(DeviceDao.class);
        final PersonFaceStorage storage = mock(PersonFaceStorage.class);
        final PersonProfileServiceImpl service = new PersonProfileServiceImpl(
                profileDao,
                bindingDao,
                eventDao,
                deviceDao,
                storage,
                mock(WorkspacePermissionService.class));

        DeviceEntity device() {
            DeviceEntity entity = new DeviceEntity();
            entity.setId("device-a");
            entity.setWorkspaceId("workspace-a");
            entity.setAgentId("agent-a");
            entity.setMacAddress("AA:BB");
            return entity;
        }

        PersonProfileEntity profile(String workspaceId) {
            PersonProfileEntity entity = new PersonProfileEntity();
            entity.setId("person-a");
            entity.setWorkspaceId(workspaceId);
            entity.setName("小明");
            entity.setFaceFilePath("workspace-a/person-a/face.jpg");
            entity.setFaceContentType("image/jpeg");
            entity.setFaceSha256("face-sha");
            entity.setFaceVersion(1L);
            entity.setRecognitionAction("NONE");
            entity.setStatus(1);
            return entity;
        }
    }
}

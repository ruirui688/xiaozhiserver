package xiaozhi.modules.person.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.person.dao.AgentFaceReleaseDao;
import xiaozhi.modules.person.dao.PersonAgentBindingDao;
import xiaozhi.modules.person.dao.PersonFaceAssetDao;
import xiaozhi.modules.person.dao.PersonRecognitionEventDao;
import xiaozhi.modules.person.dto.AgentFaceSyncRequestDTO;
import xiaozhi.modules.person.dto.DeviceFaceRecognitionEventDTO;
import xiaozhi.modules.person.entity.AgentFaceReleaseEntity;
import xiaozhi.modules.person.entity.PersonAgentBindingEntity;
import xiaozhi.modules.person.entity.PersonFaceAssetEntity;
import xiaozhi.modules.person.service.impl.PersonFaceRuntimeServiceImpl;

class PersonFaceRuntimeServiceImplTest {
    @Test
    void matchingReleaseVersionReturnsUnchangedWithoutImages() {
        Fixture fixture = new Fixture();
        AgentFaceSyncRequestDTO request = fixture.request(7L);

        var result = fixture.service.sync(request);

        assertTrue(result.isUnchanged());
        assertEquals(7L, result.getReleaseVersion());
        assertTrue(result.getUpserts().isEmpty());
    }

    @Test
    void staleInventoryReceivesOnlyChangedAndDeletedProfiles() {
        Fixture fixture = new Fixture();
        PersonAgentBindingEntity binding = fixture.binding();
        when(fixture.bindingDao.selectList(any())).thenReturn(List.of(binding));
        when(fixture.assetDao.selectBatchIds(any())).thenReturn(List.of(fixture.asset()));
        AgentFaceSyncRequestDTO request = fixture.request(6L);
        AgentFaceSyncRequestDTO.CachedProfile changed = cached("person-a", 1L, "a".repeat(64));
        AgentFaceSyncRequestDTO.CachedProfile removed = cached("person-old", 1L, "c".repeat(64));
        request.setProfiles(List.of(changed, removed));

        var result = fixture.service.sync(request);

        assertFalse(result.isUnchanged());
        assertEquals(List.of("person-old"), result.getDeletes());
        assertEquals(1, result.getUpserts().size());
        assertEquals("asset-v2", result.getUpserts().get(0).getAssetId());
    }

    @Test
    void recognitionUsesPublishedAgentSnapshot() {
        Fixture fixture = new Fixture();
        PersonAgentBindingEntity binding = fixture.binding();
        binding.setPublishedRecognitionAction("SPEAK_TEXT");
        binding.setPublishedGreetingText("欢迎回来");
        when(fixture.bindingDao.selectOne(any())).thenReturn(binding);
        when(fixture.eventDao.selectOne(any())).thenReturn(null);
        DeviceFaceRecognitionEventDTO request = new DeviceFaceRecognitionEventDTO();
        request.setMacAddress("AA:BB");
        request.setEventId("event-a");
        request.setPersonId("person-a");
        request.setConfidence(new BigDecimal("0.92"));

        var result = fixture.service.recordRecognition(request);

        assertEquals("SPEAK_TEXT", result.getActionType());
        assertEquals("欢迎回来", result.getText());
    }

    private static AgentFaceSyncRequestDTO.CachedProfile cached(String personId, long version, String sha) {
        AgentFaceSyncRequestDTO.CachedProfile item = new AgentFaceSyncRequestDTO.CachedProfile();
        item.setPersonId(personId);
        item.setFaceVersion(version);
        item.setFaceSha256(sha);
        return item;
    }

    private static final class Fixture {
        final DeviceDao deviceDao = mock(DeviceDao.class);
        final PersonAgentBindingDao bindingDao = mock(PersonAgentBindingDao.class);
        final PersonFaceAssetDao assetDao = mock(PersonFaceAssetDao.class);
        final AgentFaceReleaseDao releaseDao = mock(AgentFaceReleaseDao.class);
        final PersonRecognitionEventDao eventDao = mock(PersonRecognitionEventDao.class);
        final PersonFaceStorage storage = mock(PersonFaceStorage.class);
        final PersonFaceRuntimeServiceImpl service = new PersonFaceRuntimeServiceImpl(
                deviceDao, bindingDao, assetDao, releaseDao, eventDao, storage);

        Fixture() {
            DeviceEntity device = new DeviceEntity();
            device.setId("device-a");
            device.setMacAddress("AA:BB");
            device.setWorkspaceId("workspace-a");
            device.setAgentId("agent-a");
            when(deviceDao.selectOne(any())).thenReturn(device);
            AgentFaceReleaseEntity release = new AgentFaceReleaseEntity();
            release.setAgentId("agent-a");
            release.setReleaseVersion(7L);
            when(releaseDao.selectOne(any())).thenReturn(release);
        }

        AgentFaceSyncRequestDTO request(long version) {
            AgentFaceSyncRequestDTO request = new AgentFaceSyncRequestDTO();
            request.setMacAddress("AA:BB");
            request.setCachedAgentId("agent-a");
            request.setReleaseVersion(version);
            request.setProfiles(List.of());
            return request;
        }

        PersonAgentBindingEntity binding() {
            PersonAgentBindingEntity binding = new PersonAgentBindingEntity();
            binding.setPersonId("person-a");
            binding.setAgentId("agent-a");
            binding.setWorkspaceId("workspace-a");
            binding.setPublishedActive(1);
            binding.setPublishedPersonName("小明");
            binding.setPublishedFaceAssetId("asset-v2");
            binding.setPublishedRecognitionAction("NONE");
            return binding;
        }

        PersonFaceAssetEntity asset() {
            PersonFaceAssetEntity asset = new PersonFaceAssetEntity();
            asset.setId("asset-v2");
            asset.setPersonId("person-a");
            asset.setWorkspaceId("workspace-a");
            asset.setAssetVersion(2L);
            asset.setSha256("b".repeat(64));
            asset.setContentType("image/jpeg");
            return asset;
        }
    }
}

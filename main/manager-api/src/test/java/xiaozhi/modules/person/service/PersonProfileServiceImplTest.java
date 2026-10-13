package xiaozhi.modules.person.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import xiaozhi.common.exception.RenException;
import xiaozhi.modules.agent.dao.AgentVoicePrintDao;
import xiaozhi.modules.agent.entity.AgentVoicePrintEntity;
import xiaozhi.modules.person.dao.PersonFaceAssetDao;
import xiaozhi.modules.person.dao.PersonProfileDao;
import xiaozhi.modules.person.entity.PersonFaceAssetEntity;
import xiaozhi.modules.person.entity.PersonProfileEntity;
import xiaozhi.modules.person.service.impl.PersonProfileServiceImpl;
import xiaozhi.modules.workspace.service.WorkspacePermissionService;

class PersonProfileServiceImplTest {
    @Test
    void uploadCreatesImmutableDraftWithoutPublishing() {
        Fixture fixture = new Fixture();
        PersonProfileEntity profile = fixture.profile();
        when(fixture.profileDao.selectById("person-a")).thenReturn(profile);
        when(fixture.storage.store(any(), any(), any())).thenReturn(
                new PersonFaceStorage.StoredFace("workspace-a/person-a/v2.jpg", "image/jpeg", "b".repeat(64)));
        doAnswer(invocation -> {
            PersonFaceAssetEntity asset = invocation.getArgument(0);
            asset.setId("asset-v2");
            return 1;
        }).when(fixture.assetDao).insert(any());
        when(fixture.profileDao.updateById(profile)).thenReturn(1);
        when(fixture.publication.bindingsByPerson(any(), any())).thenReturn(Map.of());
        when(fixture.publication.agentNames(any())).thenReturn(Map.of());

        var result = fixture.service.uploadFace(
                "person-a", new MockMultipartFile("file", "face.jpg", "image/jpeg", new byte[] {1}));

        assertEquals("asset-v2", profile.getCurrentFaceAssetId());
        assertEquals(2L, result.getFaceVersion());
        assertTrue(result.isFaceReady());
        verify(fixture.publication, never()).publish(any());
    }

    @Test
    void explicitPublishDelegatesToPublicationBoundary() {
        Fixture fixture = new Fixture();
        PersonProfileEntity profile = fixture.profile();
        when(fixture.profileDao.selectById("person-a")).thenReturn(profile);
        when(fixture.publication.bindingsByPerson(any(), any())).thenReturn(Map.of());
        when(fixture.publication.agentNames(any())).thenReturn(Map.of());

        fixture.service.publish("person-a");

        verify(fixture.publication).publish(profile);
    }

    @Test
    void deletingPersonRemovesFaceFilesOnlyAfterTransactionCommit() {
        Fixture fixture = new Fixture();
        PersonProfileEntity profile = fixture.profile();
        PersonFaceAssetEntity asset = new PersonFaceAssetEntity();
        asset.setFilePath("workspace-a/person-a/v1.jpg");
        when(fixture.profileDao.selectById("person-a")).thenReturn(profile);
        when(fixture.assetDao.selectList(any())).thenReturn(List.of(asset));

        TransactionSynchronizationManager.initSynchronization();
        try {
            fixture.service.delete("person-a");

            verify(fixture.storage, never()).deleteQuietly(any());
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(synchronization -> synchronization.afterCommit());
            verify(fixture.storage).deleteQuietly("workspace-a/person-a/v1.jpg");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void listLoadsVoicePrintSummariesInOneBatch() {
        Fixture fixture = new Fixture();
        PersonProfileEntity first = fixture.profile();
        PersonProfileEntity second = fixture.profile();
        second.setId("person-b");
        second.setName("小红");
        AgentVoicePrintEntity voicePrint = fixture.voicePrint("voice-a", "person-a", "workspace-a");
        voicePrint.setAgentId("agent-a");
        voicePrint.setSourceName("小明声纹");
        voicePrint.setIntroduce("已完成录入");
        when(fixture.permission.currentWorkspaceId()).thenReturn("workspace-a");
        when(fixture.profileDao.selectList(any())).thenReturn(List.of(first, second));
        when(fixture.voicePrintDao.selectList(any())).thenReturn(List.of(voicePrint));

        var result = fixture.service.list(null);

        assertEquals(2, result.size());
        assertTrue(result.get(0).isVoicePrintReady());
        assertEquals("voice-a", result.get(0).getVoicePrintId());
        assertEquals("agent-a", result.get(0).getVoicePrintAgentId());
        assertEquals("小明声纹", result.get(0).getVoicePrintSourceName());
        assertEquals("已完成录入", result.get(0).getVoicePrintDescription());
        assertFalse(result.get(1).isVoicePrintReady());
        assertNull(result.get(1).getVoicePrintId());
        verify(fixture.voicePrintDao, times(1)).selectList(any());
    }

    @Test
    void voicePrintOptionsAreScopedToThePersonWorkspace() {
        Fixture fixture = new Fixture();
        AgentVoicePrintEntity unassigned = fixture.voicePrint("voice-free", null, "workspace-a");
        unassigned.setAgentId("agent-a");
        AgentVoicePrintEntity current = fixture.voicePrint("voice-current", "person-a", "workspace-a");
        current.setAgentId("agent-b");
        when(fixture.profileDao.selectById("person-a")).thenReturn(fixture.profile());
        when(fixture.voicePrintDao.selectPersonOptions("workspace-a", "person-a"))
                .thenReturn(List.of(unassigned, current));

        var result = fixture.service.listVoicePrintOptions("person-a");

        assertEquals(List.of("voice-free", "voice-current"), result.stream().map(item -> item.getId()).toList());
        assertEquals(List.of("agent-a", "agent-b"), result.stream().map(item -> item.getAgentId()).toList());
        verify(fixture.permission).requireWrite("workspace-a");
        verify(fixture.voicePrintDao).selectPersonOptions("workspace-a", "person-a");
    }

    @Test
    void bindingRejectsVoicePrintFromAnotherWorkspace() {
        Fixture fixture = new Fixture();
        when(fixture.profileDao.selectById("person-a")).thenReturn(fixture.profile());
        when(fixture.voicePrintDao.selectById("voice-a"))
                .thenReturn(fixture.voicePrint("voice-a", null, "workspace-b"));

        assertThrows(RenException.class, () -> fixture.service.bindVoicePrint("person-a", "voice-a"));

        verify(fixture.permission).requireWrite("workspace-a");
        verify(fixture.voicePrintDao, never()).bindPerson(any(), any(), any(), any());
    }

    @Test
    void bindingRejectsSecondVoicePrintForTheSamePerson() {
        Fixture fixture = new Fixture();
        when(fixture.profileDao.selectById("person-a")).thenReturn(fixture.profile());
        when(fixture.voicePrintDao.selectById("voice-new"))
                .thenReturn(fixture.voicePrint("voice-new", null, "workspace-a"));
        when(fixture.voicePrintDao.selectOne(any()))
                .thenReturn(fixture.voicePrint("voice-existing", "person-a", "workspace-a"));

        assertThrows(RenException.class, () -> fixture.service.bindVoicePrint("person-a", "voice-new"));

        verify(fixture.voicePrintDao, never()).bindPerson(any(), any(), any(), any());
    }

    @Test
    void bindingRejectsVoicePrintOwnedByAnotherPerson() {
        Fixture fixture = new Fixture();
        when(fixture.profileDao.selectById("person-a")).thenReturn(fixture.profile());
        when(fixture.voicePrintDao.selectById("voice-a"))
                .thenReturn(fixture.voicePrint("voice-a", "person-b", "workspace-a"));

        assertThrows(RenException.class, () -> fixture.service.bindVoicePrint("person-a", "voice-a"));

        verify(fixture.voicePrintDao, never()).bindPerson(any(), any(), any(), any());
    }

    @Test
    void bindingAndUnbindingReturnLatestPersonSummary() {
        Fixture fixture = new Fixture();
        PersonProfileEntity profile = fixture.profile();
        AgentVoicePrintEntity voicePrint = fixture.voicePrint("voice-a", null, "workspace-a");
        voicePrint.setAgentId("agent-a");
        voicePrint.setSourceName("小明声纹");
        when(fixture.profileDao.selectById("person-a")).thenReturn(profile);
        when(fixture.voicePrintDao.selectById("voice-a")).thenReturn(voicePrint);
        when(fixture.voicePrintDao.bindPerson("voice-a", "workspace-a", "person-a", null)).thenReturn(1);
        when(fixture.voicePrintDao.unbindPerson("voice-a", "workspace-a", "person-a", null)).thenReturn(1);
        when(fixture.voicePrintDao.selectList(any())).thenAnswer(invocation ->
                voicePrint.getPersonId() == null ? List.of() : List.of(voicePrint));

        var bound = fixture.service.bindVoicePrint("person-a", "voice-a");
        assertTrue(bound.isVoicePrintReady());
        assertEquals("voice-a", bound.getVoicePrintId());

        when(fixture.voicePrintDao.selectOne(any())).thenReturn(voicePrint);
        var unbound = fixture.service.unbindVoicePrint("person-a");
        assertFalse(unbound.isVoicePrintReady());
        assertNull(unbound.getVoicePrintId());
    }

    @Test
    void concurrentBindingUsesCurrentReadAndTreatsTheSameOwnerAsIdempotent() {
        Fixture fixture = new Fixture();
        PersonProfileEntity profile = fixture.profile();
        AgentVoicePrintEntity initial = fixture.voicePrint("voice-a", null, "workspace-a");
        AgentVoicePrintEntity latest = fixture.voicePrint("voice-a", "person-a", "workspace-a");
        when(fixture.profileDao.selectById("person-a")).thenReturn(profile);
        when(fixture.voicePrintDao.selectById("voice-a")).thenReturn(initial);
        when(fixture.voicePrintDao.bindPerson("voice-a", "workspace-a", "person-a", null)).thenReturn(0);
        when(fixture.voicePrintDao.selectByIdForUpdate("voice-a", "workspace-a")).thenReturn(latest);
        // 模拟 MySQL REPEATABLE READ 下普通摘要查询仍看见“未绑定”的旧快照。
        when(fixture.voicePrintDao.selectList(any())).thenReturn(List.of());

        var result = fixture.service.bindVoicePrint("person-a", "voice-a");

        assertTrue(result.isVoicePrintReady());
        assertEquals("voice-a", result.getVoicePrintId());
        verify(fixture.voicePrintDao).selectByIdForUpdate("voice-a", "workspace-a");
    }

    @Test
    void concurrentBindingRejectsAConflictingOwnerFromCurrentRead() {
        Fixture fixture = new Fixture();
        when(fixture.profileDao.selectById("person-a")).thenReturn(fixture.profile());
        when(fixture.voicePrintDao.selectById("voice-a"))
                .thenReturn(fixture.voicePrint("voice-a", null, "workspace-a"));
        when(fixture.voicePrintDao.bindPerson("voice-a", "workspace-a", "person-a", null)).thenReturn(0);
        when(fixture.voicePrintDao.selectByIdForUpdate("voice-a", "workspace-a"))
                .thenReturn(fixture.voicePrint("voice-a", "person-b", "workspace-a"));

        assertThrows(RenException.class, () -> fixture.service.bindVoicePrint("person-a", "voice-a"));
    }

    @Test
    void concurrentUnbindingUsesCurrentReadAndIsIdempotentWhenThePersonIsClear() {
        Fixture fixture = new Fixture();
        AgentVoicePrintEntity initial = fixture.voicePrint("voice-a", "person-a", "workspace-a");
        when(fixture.profileDao.selectById("person-a")).thenReturn(fixture.profile());
        when(fixture.voicePrintDao.selectOne(any())).thenReturn(initial);
        when(fixture.voicePrintDao.unbindPerson("voice-a", "workspace-a", "person-a", null)).thenReturn(0);
        when(fixture.voicePrintDao.selectByPersonForUpdate("workspace-a", "person-a")).thenReturn(null);
        // 模拟普通摘要查询仍看见解绑前关联，响应必须由权威写入结果覆盖。
        when(fixture.voicePrintDao.selectList(any())).thenReturn(List.of(initial));

        var result = fixture.service.unbindVoicePrint("person-a");

        assertFalse(result.isVoicePrintReady());
        verify(fixture.voicePrintDao).selectByPersonForUpdate("workspace-a", "person-a");
    }

    @Test
    void concurrentUnbindingRejectsAReplacementAssociationFromCurrentRead() {
        Fixture fixture = new Fixture();
        AgentVoicePrintEntity initial = fixture.voicePrint("voice-a", "person-a", "workspace-a");
        when(fixture.profileDao.selectById("person-a")).thenReturn(fixture.profile());
        when(fixture.voicePrintDao.selectOne(any())).thenReturn(initial);
        when(fixture.voicePrintDao.unbindPerson("voice-a", "workspace-a", "person-a", null)).thenReturn(0);
        when(fixture.voicePrintDao.selectByPersonForUpdate("workspace-a", "person-a"))
                .thenReturn(fixture.voicePrint("voice-b", "person-a", "workspace-a"));

        assertThrows(RenException.class, () -> fixture.service.unbindVoicePrint("person-a"));
    }

    @Test
    void unbindingPersonWithoutVoicePrintIsIdempotent() {
        Fixture fixture = new Fixture();
        when(fixture.profileDao.selectById("person-a")).thenReturn(fixture.profile());

        var result = fixture.service.unbindVoicePrint("person-a");

        assertFalse(result.isVoicePrintReady());
        verify(fixture.voicePrintDao, never()).unbindPerson(any(), any(), any(), any());
    }

    @Test
    void deletingPersonWithVoicePrintIsRejectedBeforeFaceRelease() {
        Fixture fixture = new Fixture();
        PersonProfileEntity profile = fixture.profile();
        when(fixture.profileDao.selectById("person-a")).thenReturn(profile);
        when(fixture.voicePrintDao.selectOne(any()))
                .thenReturn(fixture.voicePrint("voice-a", "person-a", "workspace-a"));

        assertThrows(RenException.class, () -> fixture.service.delete("person-a"));

        verify(fixture.publication, never()).prepareDelete(any());
        verify(fixture.profileDao, never()).deleteById("person-a");
    }

    @Test
    void rolledBackUploadRemovesNewFaceFile() {
        Fixture fixture = new Fixture();
        PersonProfileEntity profile = fixture.profile();
        when(fixture.profileDao.selectById("person-a")).thenReturn(profile);
        when(fixture.storage.store(any(), any(), any())).thenReturn(
                new PersonFaceStorage.StoredFace("workspace-a/person-a/v2.jpg", "image/jpeg", "b".repeat(64)));
        doAnswer(invocation -> {
            PersonFaceAssetEntity asset = invocation.getArgument(0);
            asset.setId("asset-v2");
            return 1;
        }).when(fixture.assetDao).insert(any());
        when(fixture.profileDao.updateById(profile)).thenReturn(1);

        TransactionSynchronizationManager.initSynchronization();
        try {
            fixture.service.uploadFace(
                    "person-a", new MockMultipartFile("file", "face.jpg", "image/jpeg", new byte[] {1}));

            verify(fixture.storage, never()).deleteQuietly(any());
            TransactionSynchronizationManager.getSynchronizations().forEach(
                    synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            verify(fixture.storage).deleteQuietly("workspace-a/person-a/v2.jpg");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static final class Fixture {
        final PersonProfileDao profileDao = mock(PersonProfileDao.class);
        final PersonFaceAssetDao assetDao = mock(PersonFaceAssetDao.class);
        final AgentVoicePrintDao voicePrintDao = mock(AgentVoicePrintDao.class);
        final PersonFaceStorage storage = mock(PersonFaceStorage.class);
        final PersonFacePublicationService publication = mock(PersonFacePublicationService.class);
        final WorkspacePermissionService permission = mock(WorkspacePermissionService.class);
        final PersonProfileServiceImpl service = new PersonProfileServiceImpl(
                profileDao, assetDao, voicePrintDao, storage, publication, permission);

        Fixture() {
            when(publication.bindingsByPerson(any(), any())).thenReturn(Map.of());
            when(publication.agentNames(any())).thenReturn(Map.of());
            when(publication.hasUnpublishedChanges(any(), any())).thenReturn(false);
            when(voicePrintDao.selectList(any())).thenReturn(List.of());
        }

        PersonProfileEntity profile() {
            PersonProfileEntity profile = new PersonProfileEntity();
            profile.setId("person-a");
            profile.setWorkspaceId("workspace-a");
            profile.setName("小明");
            profile.setStatus(1);
            profile.setFaceVersion(1L);
            profile.setCurrentFaceAssetId("asset-v1");
            return profile;
        }

        AgentVoicePrintEntity voicePrint(String id, String personId, String workspaceId) {
            AgentVoicePrintEntity voicePrint = new AgentVoicePrintEntity();
            voicePrint.setId(id);
            voicePrint.setPersonId(personId);
            voicePrint.setWorkspaceId(workspaceId);
            return voicePrint;
        }
    }
}

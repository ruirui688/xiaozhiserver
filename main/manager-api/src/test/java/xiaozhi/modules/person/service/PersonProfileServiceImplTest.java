package xiaozhi.modules.person.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
        final PersonFaceStorage storage = mock(PersonFaceStorage.class);
        final PersonFacePublicationService publication = mock(PersonFacePublicationService.class);
        final WorkspacePermissionService permission = mock(WorkspacePermissionService.class);
        final PersonProfileServiceImpl service = new PersonProfileServiceImpl(
                profileDao, assetDao, storage, publication, permission);

        Fixture() {
            when(publication.bindingsByPerson(any(), any())).thenReturn(Map.of());
            when(publication.agentNames(any())).thenReturn(Map.of());
            when(publication.hasUnpublishedChanges(any(), any())).thenReturn(false);
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
    }
}

package xiaozhi.modules.agent.support;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import xiaozhi.common.exception.RenException;

class ManagedAsrHotwordGuardTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final xiaozhi.modules.hotword.HotwordCredentialSources sources = mock(xiaozhi.modules.hotword.HotwordCredentialSources.class);
    private final ManagedAsrHotwordGuard guard = new ManagedAsrHotwordGuard(jdbc,sources);

    @Test
    void emptySelectionDoesNotQueryManagedResources() {
        guard.requireAllowed("workspace-a", "asr-a", "", List.of());
        verifyNoInteractions(jdbc);
    }

    @Test
    void legacyUnmanagedIdsRemainUsable() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        assertDoesNotThrow(() -> guard.requireAllowed("workspace-a", "asr-a", "legacy-id", null));
    }

    @Test
    void rejectsForeignManagedCandidateEvenWhenNotActive() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("workspace_id", "workspace-b", "credential_fingerprint", "source-a")));
        assertThrows(RenException.class, () -> guard.requireAllowed(
                "workspace-a", "asr-a", "legacy-id", List.of("foreign-managed-id")));
    }

    @Test
    void rejectsManagedTableFromDifferentModel() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("workspace_id", "workspace-a", "credential_fingerprint", "source-b")));
        assertThrows(RenException.class, () -> guard.requireAllowed("workspace-a", "asr-a", "managed-id", null));
    }

    @Test
    void permitsOwnedTableForMatchingSource() {
        when(sources.requireForModel("asr-a")).thenReturn("source-a");
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("workspace_id", "workspace-a", "credential_fingerprint", "source-a")));
        assertDoesNotThrow(() -> guard.requireAllowed("workspace-a", "asr-a", "managed-id", List.of("managed-id")));
    }

    @Test
    void rejectsReferencesWhileRemoteDeletionIsInProgress() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("workspace_id", "workspace-a", "credential_fingerprint", "source-a", "status", "DELETING")));
        assertThrows(RenException.class, () -> guard.requireAllowed("workspace-a", "asr-a", "managed-id", null));
    }
}

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
    private final ManagedAsrHotwordGuard guard = new ManagedAsrHotwordGuard(jdbc);

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
                .thenReturn(List.of(Map.of("workspace_id", "workspace-b", "model_id", "asr-a")));
        assertThrows(RenException.class, () -> guard.requireAllowed(
                "workspace-a", "asr-a", "legacy-id", List.of("foreign-managed-id")));
    }

    @Test
    void rejectsManagedTableFromDifferentModel() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("workspace_id", "workspace-a", "model_id", "asr-b")));
        assertThrows(RenException.class, () -> guard.requireAllowed("workspace-a", "asr-a", "managed-id", null));
    }

    @Test
    void permitsOwnedTableForMatchingModel() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("workspace_id", "workspace-a", "model_id", "asr-a")));
        assertDoesNotThrow(() -> guard.requireAllowed("workspace-a", "asr-a", "managed-id", List.of("managed-id")));
    }

    @Test
    void rejectsReferencesWhileRemoteDeletionIsInProgress() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("workspace_id", "workspace-a", "model_id", "asr-a", "status", "DELETING")));
        assertThrows(RenException.class, () -> guard.requireAllowed("workspace-a", "asr-a", "managed-id", null));
    }
}

package xiaozhi.modules.memory.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import xiaozhi.modules.memory.service.ManagedMemorySnapshotService;
import xiaozhi.modules.memory.service.MemoryPolicyService;

class MemoryPolicyControllerTest {
    @Test
    void nonManagedAgentPreviewIsEmptyForUnidentifiedSpeaker() {
        var policies = mock(MemoryPolicyService.class);
        var snapshots = mock(ManagedMemorySnapshotService.class);
        when(policies.speakers("agent")).thenReturn(List.of());
        when(snapshots.build("agent", "mac", List.of())).thenReturn(new LinkedHashMap<>(Map.of(
                "status", "ready", "shared_context", "- old managed fact\n", "speaker_contexts", Map.of(),
                "context_max_chars", 4000, "context_max_items", 30, "truncated", false)));
        var controller = new MemoryPolicyController(policies, snapshots);
        var response = controller.preview("agent", new MemoryPolicyController.PreviewRequest("mac", null)).getData();
        assertEquals("", response.get("selected_context"));
        assertEquals("disabled", ((Map<?, ?>) response.get("snapshot")).get("status"));
        verify(policies).requireAccess("agent", false);
        verify(policies).requireDevice("agent", "mac");
    }
}

package xiaozhi.modules.memory.controller;

import java.util.Map;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.*;
import lombok.RequiredArgsConstructor;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.memory.dto.MemoryPolicyDTO;
import xiaozhi.modules.memory.service.ManagedMemorySnapshotService;
import xiaozhi.modules.memory.service.MemoryPolicyService;

@RestController
@RequestMapping("agent/{agentId}/memories")
@RequiredArgsConstructor
@RequiresPermissions("sys:role:normal")
public class MemoryPolicyController {
    private final MemoryPolicyService policies;
    private final ManagedMemorySnapshotService snapshots;

    @GetMapping("policy")
    public Result<Map<String, Object>> get(@PathVariable String agentId) {
        return new Result<Map<String, Object>>().ok(policies.managementView(agentId));
    }

    @PutMapping("policy")
    public Result<Void> save(@PathVariable String agentId, @RequestBody @Valid MemoryPolicyDTO policy) {
        policies.save(agentId, policy);
        return new Result<Void>().ok(null);
    }

    public record PreviewRequest(@NotBlank @Size(max = 64) String deviceId, @Size(max = 128) String speakerId) {}

    @PostMapping("preview")
    public Result<Map<String, Object>> preview(@PathVariable String agentId, @RequestBody @Valid PreviewRequest request) {
        policies.requireAccess(agentId, false);
        policies.requireDevice(agentId, request.deviceId());
        policies.requireSpeaker(agentId, request.speakerId());
        var snapshot = snapshots.build(agentId, request.deviceId(), policies.speakers(agentId));
        if (!policies.managed(agentId)) {
            snapshot.put("status", "disabled");
            snapshot.put("shared_context", "");
            snapshot.put("speaker_contexts", Map.of());
        }
        String selected = ManagedMemorySnapshotService.selectedContext(snapshot, request.speakerId());
        Map<?, ?> parts = (Map<?, ?>) snapshot.get("speaker_contexts");
        String speaker = org.apache.commons.lang3.StringUtils.defaultString(request.speakerId());
        String privateText = parts.containsKey(speaker) ? String.valueOf(parts.get(speaker)) : "";
        boolean truncated = Boolean.TRUE.equals(snapshot.get("truncated")) ||
                (privateText + snapshot.get("shared_context")).lines().count() > selected.lines().count();
        return new Result<Map<String, Object>>().ok(Map.of("snapshot", snapshot, "selected_context", selected,
                "budgets", Map.of("maxChars", snapshot.get("context_max_chars"), "maxItems", snapshot.get("context_max_items")),
                "usage", Map.of("chars", ManagedMemorySnapshotService.escapedLength(selected), "items", selected.isEmpty() ? 0 : selected.lines().count()),
                "excluded", Map.of("truncated", truncated)));
    }
}

package xiaozhi.modules.memory.controller;

import java.util.List;
import java.util.Map;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.*;
import lombok.RequiredArgsConstructor;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.memory.service.processing.MemorySessionTaskService;

@RestController
@RequestMapping("agent/{agentId}/memories/session-tasks")
@RequiresPermissions("sys:role:normal")
@RequiredArgsConstructor
public class MemorySessionTaskController {
    private final MemorySessionTaskService tasks;

    @GetMapping
    public Result<List<Map<String, Object>>> list(@PathVariable String agentId) {
        return new Result<List<Map<String, Object>>>().ok(tasks.list(agentId));
    }

    @PostMapping("{taskId}/retry")
    public Result<Void> retry(@PathVariable String agentId, @PathVariable String taskId) {
        tasks.retry(agentId, taskId);
        return new Result<Void>().ok(null);
    }
}

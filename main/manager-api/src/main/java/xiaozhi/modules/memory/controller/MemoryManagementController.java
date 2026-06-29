package xiaozhi.modules.memory.controller;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import xiaozhi.common.user.UserDetail;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.memory.dto.MemoryFactDTO;
import xiaozhi.modules.memory.dto.MemoryForgetDTO;
import xiaozhi.modules.memory.dto.MemoryRememberDTO;
import xiaozhi.modules.memory.service.MemoryService;
import xiaozhi.modules.memory.vo.MemoryDashboardVO;
import xiaozhi.modules.security.user.SecurityUser;

@RestController
@RequestMapping("agent/{agentId}/memories")
@Tag(name = "智能体长期记忆管理")
@AllArgsConstructor
public class MemoryManagementController {
    private final MemoryService memoryService;

    @GetMapping
    @Operation(summary = "获取智能体长期记忆维护视图")
    @RequiresPermissions("sys:role:normal")
    public Result<MemoryDashboardVO> dashboard(@PathVariable String agentId) {
        return new Result<MemoryDashboardVO>().ok(memoryService.dashboard(agentId));
    }

    @PostMapping("facts")
    @Operation(summary = "手动新增长期事实记忆")
    @RequiresPermissions("sys:role:normal")
    public Result<String> createFact(@PathVariable String agentId, @RequestBody MemoryFactDTO dto) {
        return new Result<String>().ok(memoryService.createFact(agentId, dto, "user", currentUserId()));
    }

    @PutMapping("facts/{memoryId}")
    @Operation(summary = "更新长期事实记忆")
    @RequiresPermissions("sys:role:normal")
    public Result<Void> updateFact(@PathVariable String agentId, @PathVariable String memoryId,
                                   @RequestBody MemoryFactDTO dto) {
        memoryService.updateFact(agentId, memoryId, dto, "user", currentUserId());
        return new Result<Void>().ok(null);
    }

    @PostMapping("facts/{memoryId}/archive")
    @Operation(summary = "归档长期事实记忆")
    @RequiresPermissions("sys:role:normal")
    public Result<Void> archiveFact(@PathVariable String agentId, @PathVariable String memoryId) {
        memoryService.archiveFact(agentId, memoryId, "user", currentUserId());
        return new Result<Void>().ok(null);
    }

    @DeleteMapping("facts/{memoryId}")
    @Operation(summary = "删除长期事实记忆")
    @RequiresPermissions("sys:role:normal")
    public Result<Void> deleteFact(@PathVariable String agentId, @PathVariable String memoryId) {
        memoryService.deleteFact(agentId, memoryId, "user", currentUserId());
        return new Result<Void>().ok(null);
    }

    @PostMapping("candidates/{candidateId}/confirm")
    @Operation(summary = "确认候选记忆")
    @RequiresPermissions("sys:role:normal")
    public Result<Void> confirmCandidate(@PathVariable String agentId, @PathVariable String candidateId) {
        memoryService.confirmCandidate(agentId, candidateId, "user", currentUserId());
        return new Result<Void>().ok(null);
    }

    @PostMapping("candidates/{candidateId}/reject")
    @Operation(summary = "拒绝候选记忆")
    @RequiresPermissions("sys:role:normal")
    public Result<Void> rejectCandidate(@PathVariable String agentId, @PathVariable String candidateId) {
        memoryService.rejectCandidate(agentId, candidateId, "user", currentUserId());
        return new Result<Void>().ok(null);
    }

    @PostMapping("remember")
    @Operation(summary = "手动记住")
    @RequiresPermissions("sys:role:normal")
    public Result<Void> remember(@PathVariable String agentId, @RequestBody MemoryRememberDTO dto) {
        dto.setAgentId(agentId);
        memoryService.remember(dto, "user", currentUserId());
        return new Result<Void>().ok(null);
    }

    @PostMapping("forget")
    @Operation(summary = "手动忘记")
    @RequiresPermissions("sys:role:normal")
    public Result<Void> forget(@PathVariable String agentId, @RequestBody MemoryForgetDTO dto) {
        dto.setAgentId(agentId);
        memoryService.forget(dto, "user", currentUserId());
        return new Result<Void>().ok(null);
    }

    private String currentUserId() {
        UserDetail user = SecurityUser.getUser();
        return user == null ? null : String.valueOf(user.getId());
    }
}

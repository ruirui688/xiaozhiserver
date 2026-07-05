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
import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.user.UserDetail;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.agent.service.AgentService;
import xiaozhi.modules.memory.dto.MemoryFactDTO;
import xiaozhi.modules.memory.dto.MemoryForgetDTO;
import xiaozhi.modules.memory.dto.MemoryRememberDTO;
import xiaozhi.modules.memory.dto.MemoryRebuildRequestDTO;
import xiaozhi.modules.memory.service.MemoryService;
import xiaozhi.modules.memory.vo.MemoryDashboardVO;
import xiaozhi.modules.memory.vo.MemoryRebuildResultVO;
import xiaozhi.modules.security.user.SecurityUser;

@RestController
@RequestMapping("agent/{agentId}/memories")
@Tag(name = "智能体长期记忆管理")
@AllArgsConstructor
public class MemoryManagementController {
    private final MemoryService memoryService;
    private final AgentService agentService;

    @GetMapping
    @Operation(summary = "获取智能体长期记忆维护视图")
    @RequiresPermissions("sys:role:normal")
    public Result<MemoryDashboardVO> dashboard(@PathVariable String agentId) {
        if (!agentService.checkAgentPermission(agentId, SecurityUser.getUserId())) {
            throw new RenException(ErrorCode.NO_PERMISSION);
        }
        return new Result<MemoryDashboardVO>().ok(memoryService.dashboard(agentId));
    }

    @PostMapping("facts")
    @Operation(summary = "手动新增长期事实记忆")
    @RequiresPermissions("sys:role:normal")
    public Result<String> createFact(@PathVariable String agentId, @RequestBody MemoryFactDTO dto) {
        if (!agentService.checkAgentPermission(agentId, SecurityUser.getUserId())) {
            throw new RenException(ErrorCode.NO_PERMISSION);
        }
        return new Result<String>().ok(memoryService.createFact(agentId, dto, "user", currentUserId()));
    }

    @PutMapping("facts/{memoryId}")
    @Operation(summary = "更新长期事实记忆")
    @RequiresPermissions("sys:role:normal")
    public Result<Void> updateFact(@PathVariable String agentId, @PathVariable String memoryId,
                                   @RequestBody MemoryFactDTO dto) {
        if (!agentService.checkAgentPermission(agentId, SecurityUser.getUserId())) {
            throw new RenException(ErrorCode.NO_PERMISSION);
        }
        memoryService.updateFact(agentId, memoryId, dto, "user", currentUserId());
        return new Result<Void>().ok(null);
    }

    @PostMapping("facts/{memoryId}/archive")
    @Operation(summary = "归档长期事实记忆")
    @RequiresPermissions("sys:role:normal")
    public Result<Void> archiveFact(@PathVariable String agentId, @PathVariable String memoryId) {
        if (!agentService.checkAgentPermission(agentId, SecurityUser.getUserId())) {
            throw new RenException(ErrorCode.NO_PERMISSION);
        }
        memoryService.archiveFact(agentId, memoryId, "user", currentUserId());
        return new Result<Void>().ok(null);
    }

    @DeleteMapping("facts/{memoryId}")
    @Operation(summary = "删除长期事实记忆")
    @RequiresPermissions("sys:role:normal")
    public Result<Void> deleteFact(@PathVariable String agentId, @PathVariable String memoryId) {
        if (!agentService.checkAgentPermission(agentId, SecurityUser.getUserId())) {
            throw new RenException(ErrorCode.NO_PERMISSION);
        }
        memoryService.deleteFact(agentId, memoryId, "user", currentUserId());
        return new Result<Void>().ok(null);
    }

    @PostMapping("candidates/{candidateId}/confirm")
    @Operation(summary = "确认候选记忆")
    @RequiresPermissions("sys:role:normal")
    public Result<Void> confirmCandidate(@PathVariable String agentId, @PathVariable String candidateId) {
        if (!agentService.checkAgentPermission(agentId, SecurityUser.getUserId())) {
            throw new RenException(ErrorCode.NO_PERMISSION);
        }
        memoryService.confirmCandidate(agentId, candidateId, "user", currentUserId());
        return new Result<Void>().ok(null);
    }

    @PostMapping("candidates/{candidateId}/reject")
    @Operation(summary = "拒绝候选记忆")
    @RequiresPermissions("sys:role:normal")
    public Result<Void> rejectCandidate(@PathVariable String agentId, @PathVariable String candidateId) {
        if (!agentService.checkAgentPermission(agentId, SecurityUser.getUserId())) {
            throw new RenException(ErrorCode.NO_PERMISSION);
        }
        memoryService.rejectCandidate(agentId, candidateId, "user", currentUserId());
        return new Result<Void>().ok(null);
    }

    @PostMapping("clear")
    @Operation(summary = "清空当前智能体长期记忆")
    @RequiresPermissions("sys:role:normal")
    public Result<MemoryRebuildResultVO> clear(@PathVariable String agentId) {
        if (!agentService.checkAgentPermission(agentId, SecurityUser.getUserId())) {
            throw new RenException(ErrorCode.NO_PERMISSION);
        }
        return new Result<MemoryRebuildResultVO>().ok(memoryService.clearMemories(agentId, "user", currentUserId()));
    }

    @PostMapping("rebuild-preview")
    @Operation(summary = "从最近聊天记录预览重建长期记忆")
    @RequiresPermissions("sys:role:normal")
    public Result<MemoryRebuildResultVO> rebuildPreview(@PathVariable String agentId,
                                                        @RequestBody(required = false) MemoryRebuildRequestDTO dto) {
        if (!agentService.checkAgentPermission(agentId, SecurityUser.getUserId())) {
            throw new RenException(ErrorCode.NO_PERMISSION);
        }
        if (dto == null) {
            dto = new MemoryRebuildRequestDTO();
        }
        dto.setDryRun(true);
        return new Result<MemoryRebuildResultVO>().ok(
                memoryService.rebuildFromRecentChatHistory(agentId, dto, "user", currentUserId()));
    }

    @PostMapping("rebuild")
    @Operation(summary = "从最近聊天记录重建长期记忆")
    @RequiresPermissions("sys:role:normal")
    public Result<MemoryRebuildResultVO> rebuild(@PathVariable String agentId,
                                                 @RequestBody(required = false) MemoryRebuildRequestDTO dto) {
        if (!agentService.checkAgentPermission(agentId, SecurityUser.getUserId())) {
            throw new RenException(ErrorCode.NO_PERMISSION);
        }
        if (dto == null) {
            dto = new MemoryRebuildRequestDTO();
        }
        dto.setDryRun(false);
        return new Result<MemoryRebuildResultVO>().ok(
                memoryService.rebuildFromRecentChatHistory(agentId, dto, "user", currentUserId()));
    }

    @PostMapping("review-preview")
    @Operation(summary = "预览当前生效记忆质量重审")
    @RequiresPermissions("sys:role:normal")
    public Result<MemoryRebuildResultVO> reviewPreview(@PathVariable String agentId) {
        if (!agentService.checkAgentPermission(agentId, SecurityUser.getUserId())) {
            throw new RenException(ErrorCode.NO_PERMISSION);
        }
        return new Result<MemoryRebuildResultVO>().ok(
                memoryService.reviewActiveMemories(agentId, true, "user", currentUserId()));
    }

    @PostMapping("review")
    @Operation(summary = "执行当前生效记忆质量重审")
    @RequiresPermissions("sys:role:normal")
    public Result<MemoryRebuildResultVO> review(@PathVariable String agentId) {
        if (!agentService.checkAgentPermission(agentId, SecurityUser.getUserId())) {
            throw new RenException(ErrorCode.NO_PERMISSION);
        }
        return new Result<MemoryRebuildResultVO>().ok(
                memoryService.reviewActiveMemories(agentId, false, "user", currentUserId()));
    }

    @PostMapping("remember")
    @Operation(summary = "手动记住")
    @RequiresPermissions("sys:role:normal")
    public Result<Void> remember(@PathVariable String agentId, @RequestBody MemoryRememberDTO dto) {
        if (!agentService.checkAgentPermission(agentId, SecurityUser.getUserId())) {
            throw new RenException(ErrorCode.NO_PERMISSION);
        }
        dto.setAgentId(agentId);
        memoryService.remember(dto, "user", currentUserId());
        return new Result<Void>().ok(null);
    }

    @PostMapping("forget")
    @Operation(summary = "手动忘记")
    @RequiresPermissions("sys:role:normal")
    public Result<Void> forget(@PathVariable String agentId, @RequestBody MemoryForgetDTO dto) {
        if (!agentService.checkAgentPermission(agentId, SecurityUser.getUserId())) {
            throw new RenException(ErrorCode.NO_PERMISSION);
        }
        dto.setAgentId(agentId);
        memoryService.forget(dto, "user", currentUserId());
        return new Result<Void>().ok(null);
    }

    private String currentUserId() {
        UserDetail user = SecurityUser.getUser();
        return user == null ? null : String.valueOf(user.getId());
    }
}

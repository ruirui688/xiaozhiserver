package xiaozhi.modules.agent.controller;

import java.util.List;
import java.util.Map;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.user.UserDetail;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.agent.dto.AgentFeishuConfigSaveDTO;
import xiaozhi.modules.agent.service.AgentFeishuService;
import xiaozhi.modules.agent.service.AgentService;
import xiaozhi.modules.agent.vo.AgentFeishuConfigVO;
import xiaozhi.modules.agent.vo.AgentInfoVO;
import xiaozhi.modules.agent.vo.FeishuChatVO;
import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.modules.workspace.service.WorkspacePermissionService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/agent/{agentId}/feishu")
public class AgentFeishuController {
    private final AgentFeishuService feishuService;
    private final AgentService agentService;
    private final WorkspacePermissionService workspacePermissionService;

    @GetMapping
    @RequiresPermissions("sys:role:normal")
    public Result<AgentFeishuConfigVO> getConfig(@PathVariable String agentId) {
        requireAgentAccess(agentId, false);
        return new Result<AgentFeishuConfigVO>().ok(feishuService.getConfig(agentId));
    }

    @PutMapping
    @RequiresPermissions("sys:role:normal")
    public Result<Void> saveConfig(
            @PathVariable String agentId,
            @Valid @RequestBody AgentFeishuConfigSaveDTO dto) {
        requireAgentAccess(agentId, true);
        feishuService.saveConfig(agentId, dto, SecurityUser.getUser().getId());
        return new Result<Void>().ok(null);
    }

    @DeleteMapping
    @RequiresPermissions("sys:role:normal")
    public Result<Void> deleteConfig(@PathVariable String agentId) {
        requireAgentAccess(agentId, true);
        feishuService.deleteConfig(agentId);
        return new Result<Void>().ok(null);
    }

    @PostMapping("/test")
    @RequiresPermissions("sys:role:normal")
    public Result<List<FeishuChatVO>> testConnection(
            @PathVariable String agentId,
            @Valid @RequestBody AgentFeishuConfigSaveDTO dto) {
        requireAgentAccess(agentId, true);
        return new Result<List<FeishuChatVO>>().ok(feishuService.testConnection(agentId, dto));
    }

    @GetMapping("/chats")
    @RequiresPermissions("sys:role:normal")
    public Result<List<FeishuChatVO>> listChats(@PathVariable String agentId) {
        requireAgentAccess(agentId, false);
        return new Result<List<FeishuChatVO>>().ok(feishuService.listChats(agentId));
    }

    @PostMapping("/test-message")
    @RequiresPermissions("sys:role:normal")
    public Result<Map<String, Object>> sendTestMessage(@PathVariable String agentId) {
        requireAgentAccess(agentId, true);
        return new Result<Map<String, Object>>().ok(feishuService.sendTestMessage(agentId));
    }

    @PostMapping("/events")
    public Map<String, Object> receiveEvent(
            @PathVariable String agentId,
            @Valid @RequestBody Map<String, Object> payload) {
        return feishuService.handleEvent(agentId, payload);
    }

    private void requireAgentAccess(String agentId, boolean write) {
        UserDetail user = SecurityUser.getUser();
        if (!agentService.checkAgentPermission(agentId, user.getId())) {
            throw new xiaozhi.common.exception.RenException(ErrorCode.AGENT_NOT_FOUND);
        }
        AgentInfoVO agent = agentService.getAgentById(agentId);
        if (agent == null) {
            throw new xiaozhi.common.exception.RenException(ErrorCode.AGENT_NOT_FOUND);
        }
        if (write) {
            workspacePermissionService.requireWrite(agent.getWorkspaceId());
        }
    }
}

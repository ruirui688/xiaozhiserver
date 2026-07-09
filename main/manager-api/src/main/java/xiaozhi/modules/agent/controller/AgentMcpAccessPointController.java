package xiaozhi.modules.agent.controller;

import java.util.List;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.user.UserDetail;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.agent.dto.McpNoCodeToolDTO;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.agent.service.AgentMcpAccessPointService;
import xiaozhi.modules.agent.service.AgentMcpNoCodeToolService;
import xiaozhi.modules.agent.service.AgentService;
import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.modules.workspace.service.WorkspacePermissionService;

@Tag(name = "智能体Mcp接入点管理")
@RequiredArgsConstructor
@RestController
@RequestMapping("/agent/mcp")
public class AgentMcpAccessPointController {
    private final AgentMcpAccessPointService agentMcpAccessPointService;
    private final AgentMcpNoCodeToolService agentMcpNoCodeToolService;
    private final AgentService agentService;
    private final WorkspacePermissionService workspacePermissionService;

    /**
     * 获取智能体的Mcp接入点地址
     * 
     * @param agentId 智能体id
     * @return 返回错误提醒或者Mcp接入点地址
     */
    @Operation(summary = "获取智能体的Mcp接入点地址")
    @GetMapping("/address/{agentId}")
    @RequiresPermissions("sys:role:normal")
    public Result<String> getAgentMcpAccessAddress(@PathVariable("agentId") String agentId) {
        // 获取当前用户
        UserDetail user = SecurityUser.getUser();

        // 检查权限
        if (!agentService.checkAgentPermission(agentId, user.getId())) {
            return new Result<String>().error(ErrorCode.MCP_ACCESS_POINT_ADDRESS_NO_PERMISSION);
        }
        String agentMcpAccessAddress = agentMcpAccessPointService.getAgentMcpAccessAddress(agentId);
        if (agentMcpAccessAddress == null) {
            return new Result<String>().error(ErrorCode.MCP_ACCESS_POINT_ADDRESS_NOT_CONFIGURED);
        }
        return new Result<String>().ok(agentMcpAccessAddress);
    }

    @Operation(summary = "获取智能体的Mcp工具列表")
    @GetMapping("/tools/{agentId}")
    @RequiresPermissions("sys:role:normal")
    public Result<List<String>> getAgentMcpToolsList(@PathVariable("agentId") String agentId) {
        // 获取当前用户
        UserDetail user = SecurityUser.getUser();

        // 检查权限
        if (!agentService.checkAgentPermission(agentId, user.getId())) {
            return new Result<List<String>>().error(ErrorCode.MCP_ACCESS_POINT_TOOLS_LIST_NO_PERMISSION);
        }
        List<String> agentMcpToolsList = agentMcpAccessPointService.getAgentMcpToolsList(agentId);
        return new Result<List<String>>().ok(agentMcpToolsList);
    }

    @Operation(summary = "获取智能体可视化MCP HTTP工具配置")
    @GetMapping("/no-code-tools/{agentId}")
    @RequiresPermissions("sys:role:normal")
    public Result<List<McpNoCodeToolDTO>> getNoCodeTools(@PathVariable("agentId") String agentId) {
        UserDetail user = SecurityUser.getUser();
        if (!agentService.checkAgentPermission(agentId, user.getId())) {
            return new Result<List<McpNoCodeToolDTO>>().error(ErrorCode.MCP_ACCESS_POINT_TOOLS_LIST_NO_PERMISSION);
        }
        return new Result<List<McpNoCodeToolDTO>>().ok(agentMcpNoCodeToolService.getToolsByAgentId(agentId));
    }

    @Operation(summary = "保存智能体可视化MCP HTTP工具配置")
    @PutMapping("/no-code-tools/{agentId}")
    @RequiresPermissions("sys:role:normal")
    public Result<Void> saveNoCodeTools(
            @PathVariable("agentId") String agentId,
            @RequestBody List<McpNoCodeToolDTO> tools) {
        UserDetail user = SecurityUser.getUser();
        if (!agentService.checkAgentPermission(agentId, user.getId())) {
            return new Result<Void>().error(ErrorCode.MCP_ACCESS_POINT_TOOLS_LIST_NO_PERMISSION);
        }
        AgentEntity agent = agentService.getAgentById(agentId);
        if (agent == null) {
            return new Result<Void>().error(ErrorCode.AGENT_NOT_FOUND);
        }
        workspacePermissionService.requireWrite(agent.getWorkspaceId());
        agentMcpNoCodeToolService.saveToolsByAgentId(agentId, tools);
        return new Result<Void>().ok(null);
    }
}

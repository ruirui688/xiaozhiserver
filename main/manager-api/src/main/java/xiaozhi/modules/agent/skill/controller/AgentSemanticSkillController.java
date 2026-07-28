package xiaozhi.modules.agent.skill.controller;

import java.util.List;

import org.apache.commons.lang3.StringUtils;
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
import xiaozhi.common.exception.RenException;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.agent.service.AgentService;
import xiaozhi.modules.agent.skill.dto.AgentSemanticSkillOrderDTO;
import xiaozhi.modules.agent.skill.dto.AgentSemanticSkillSaveDTO;
import xiaozhi.modules.agent.skill.service.AgentSemanticSkillService;
import xiaozhi.modules.agent.skill.vo.AgentSemanticSkillVO;
import xiaozhi.modules.agent.vo.AgentInfoVO;
import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.modules.workspace.service.WorkspacePermissionService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/agent/{agentId}/skills")
public class AgentSemanticSkillController {
    private final AgentSemanticSkillService skillService;
    private final AgentService agentService;
    private final WorkspacePermissionService workspacePermissionService;

    @GetMapping
    @RequiresPermissions("sys:role:normal")
    public Result<List<AgentSemanticSkillVO>> list(@PathVariable String agentId) {
        requireAgentAccess(agentId, false);
        return new Result<List<AgentSemanticSkillVO>>().ok(skillService.list(agentId));
    }

    @PostMapping
    @RequiresPermissions("sys:role:normal")
    public Result<AgentSemanticSkillVO> create(
            @PathVariable String agentId,
            @Valid @RequestBody AgentSemanticSkillSaveDTO dto) {
        requireAgentAccess(agentId, true);
        return new Result<AgentSemanticSkillVO>().ok(
                skillService.create(agentId, dto, SecurityUser.getUserId()));
    }

    @PutMapping("/{skillId}")
    @RequiresPermissions("sys:role:normal")
    public Result<AgentSemanticSkillVO> update(
            @PathVariable String agentId,
            @PathVariable String skillId,
            @Valid @RequestBody AgentSemanticSkillSaveDTO dto) {
        requireAgentAccess(agentId, true);
        return new Result<AgentSemanticSkillVO>().ok(
                skillService.update(agentId, skillId, dto, SecurityUser.getUserId()));
    }

    @DeleteMapping("/{skillId}")
    @RequiresPermissions("sys:role:normal")
    public Result<Void> delete(@PathVariable String agentId, @PathVariable String skillId) {
        requireAgentAccess(agentId, true);
        skillService.delete(agentId, skillId);
        return new Result<Void>().ok(null);
    }

    @PutMapping("/order")
    @RequiresPermissions("sys:role:normal")
    public Result<Void> updateOrder(
            @PathVariable String agentId,
            @Valid @RequestBody AgentSemanticSkillOrderDTO dto) {
        requireAgentAccess(agentId, true);
        skillService.updateOrder(agentId, dto, SecurityUser.getUserId());
        return new Result<Void>().ok(null);
    }

    private void requireAgentAccess(String agentId, boolean write) {
        if (!agentService.checkAgentPermission(agentId, SecurityUser.getUserId())) {
            throw new RenException(ErrorCode.AGENT_NOT_FOUND);
        }
        AgentInfoVO agent = agentService.getAgentById(agentId);
        if (StringUtils.isBlank(agent.getWorkspaceId())) {
            return;
        }
        if (write) {
            workspacePermissionService.requireWrite(agent.getWorkspaceId());
        } else {
            workspacePermissionService.requireRead(agent.getWorkspaceId());
        }
    }
}

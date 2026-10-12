package xiaozhi.modules.workspace.controller;

import java.util.List;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.validation.annotation.Validated;
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
import xiaozhi.common.utils.Result;
import xiaozhi.modules.workspace.dto.WorkspaceCreateDTO;
import xiaozhi.modules.workspace.dto.WorkspaceInviteDTO;
import xiaozhi.modules.workspace.dto.WorkspaceRoleUpdateDTO;
import xiaozhi.modules.agent.service.impl.WorkspaceAgentQuotaService;
import xiaozhi.modules.workspace.service.WorkspaceService;
import xiaozhi.modules.workspace.vo.WorkspaceInvitationVO;
import xiaozhi.modules.workspace.vo.WorkspaceMemberVO;
import xiaozhi.modules.workspace.vo.WorkspaceVO;
import xiaozhi.modules.workspace.vo.WorkspaceAgentQuotaVO;

@RestController
@RequestMapping("/workspace")
@Tag(name = "组织工作空间")
@AllArgsConstructor
public class WorkspaceController {
    private final WorkspaceService workspaceService;
    private final WorkspaceAgentQuotaService workspaceAgentQuotaService;

    @GetMapping("/list")
    @Operation(summary = "当前用户工作空间列表")
    @RequiresPermissions("sys:role:normal")
    public Result<List<WorkspaceVO>> list() {
        return new Result<List<WorkspaceVO>>().ok(workspaceService.listCurrentUserWorkspaces());
    }

    @GetMapping("/current")
    @Operation(summary = "当前工作空间")
    @RequiresPermissions("sys:role:normal")
    public Result<WorkspaceVO> current() {
        return new Result<WorkspaceVO>().ok(workspaceService.getCurrentWorkspace());
    }

    @GetMapping("/current/quota")
    @Operation(summary = "当前工作空间智能体配额")
    @RequiresPermissions("sys:role:normal")
    public Result<WorkspaceAgentQuotaVO> currentQuota() {
        String workspaceId = workspaceService.getCurrentWorkspaceEntity().getId();
        return new Result<WorkspaceAgentQuotaVO>().ok(workspaceAgentQuotaService.getWorkspaceQuota(workspaceId));
    }

    @PostMapping
    @Operation(summary = "创建工作空间（已禁用，默认工作空间支持邀请协作）")
    @RequiresPermissions("sys:role:normal")
    public Result<WorkspaceVO> create(@RequestBody @Validated WorkspaceCreateDTO dto) {
        return new Result<WorkspaceVO>().ok(workspaceService.createWorkspace(dto));
    }

    @PutMapping("/{workspaceId}")
    @Operation(summary = "更新工作空间")
    @RequiresPermissions("sys:role:normal")
    public Result<WorkspaceVO> update(@PathVariable String workspaceId, @RequestBody @Validated WorkspaceCreateDTO dto) {
        return new Result<WorkspaceVO>().ok(workspaceService.updateWorkspace(workspaceId, dto));
    }

    @GetMapping("/{workspaceId}/members")
    @Operation(summary = "工作空间成员列表")
    @RequiresPermissions("sys:role:normal")
    public Result<List<WorkspaceMemberVO>> members(@PathVariable String workspaceId) {
        return new Result<List<WorkspaceMemberVO>>().ok(workspaceService.listMembers(workspaceId));
    }

    @GetMapping("/{workspaceId}/invitations")
    @Operation(summary = "工作空间待接受邀请列表")
    @RequiresPermissions("sys:role:normal")
    public Result<List<WorkspaceInvitationVO>> invitations(@PathVariable String workspaceId) {
        return new Result<List<WorkspaceInvitationVO>>().ok(
                workspaceService.listPendingInvitations(workspaceId));
    }

    @PostMapping("/{workspaceId}/invitations")
    @Operation(summary = "邀请成员")
    @RequiresPermissions("sys:role:normal")
    public Result<WorkspaceInvitationVO> invite(@PathVariable String workspaceId,
            @RequestBody @Validated WorkspaceInviteDTO dto) {
        return new Result<WorkspaceInvitationVO>().ok(workspaceService.invite(workspaceId, dto));
    }

    @DeleteMapping("/{workspaceId}/invitations/{invitationId}")
    @Operation(summary = "撤销待接受邀请")
    @RequiresPermissions("sys:role:normal")
    public Result<Void> revokeInvitation(@PathVariable String workspaceId,
            @PathVariable String invitationId) {
        workspaceService.revokeInvitation(workspaceId, invitationId);
        return new Result<Void>().ok(null);
    }

    @PostMapping("/invitations/{code}/accept")
    @Operation(summary = "接受邀请")
    @RequiresPermissions("sys:role:normal")
    public Result<WorkspaceVO> accept(@PathVariable String code) {
        return new Result<WorkspaceVO>().ok(workspaceService.acceptInvitation(code));
    }

    @DeleteMapping("/{workspaceId}/members/{userId}")
    @Operation(summary = "移除成员")
    @RequiresPermissions("sys:role:normal")
    public Result<Void> removeMember(@PathVariable String workspaceId, @PathVariable Long userId) {
        workspaceService.removeMember(workspaceId, userId);
        return new Result<Void>().ok(null);
    }

    @DeleteMapping("/{workspaceId}/members/me")
    @Operation(summary = "退出工作空间")
    @RequiresPermissions("sys:role:normal")
    public Result<Void> leave(@PathVariable String workspaceId) {
        workspaceService.leaveWorkspace(workspaceId);
        return new Result<Void>().ok(null);
    }

    @PutMapping("/{workspaceId}/members/{userId}/role")
    @Operation(summary = "修改成员角色")
    @RequiresPermissions("sys:role:normal")
    public Result<Void> updateRole(@PathVariable String workspaceId, @PathVariable Long userId,
            @RequestBody @Validated WorkspaceRoleUpdateDTO dto) {
        workspaceService.updateMemberRole(workspaceId, userId, dto);
        return new Result<Void>().ok(null);
    }
}

package xiaozhi.modules.agent.controller;

import org.apache.commons.lang3.StringUtils;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.agent.dto.AgentVoicePrintEnrollmentCreateDTO;
import xiaozhi.modules.agent.dto.AgentVoicePrintEnrollmentSampleDTO;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.agent.service.AgentService;
import xiaozhi.modules.agent.service.AgentVoicePrintEnrollmentService;
import xiaozhi.modules.agent.vo.AgentVoicePrintEnrollmentVO;
import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.modules.workspace.service.WorkspacePermissionService;

@Tag(name = "智能体声纹采集任务")
@AllArgsConstructor
@RestController
@RequestMapping("/agent/voice-print/enrollments")
public class AgentVoicePrintEnrollmentController {
    private final AgentVoicePrintEnrollmentService enrollmentService;
    private final AgentService agentService;
    private final WorkspacePermissionService workspacePermissionService;

    @PostMapping
    @Operation(summary = "创建声纹采集任务")
    @RequiresPermissions("sys:role:normal")
    public Result<AgentVoicePrintEnrollmentVO> create(
            @RequestBody @Valid AgentVoicePrintEnrollmentCreateDTO dto) {
        requireAgentWrite(dto.getAgentId());
        return new Result<AgentVoicePrintEnrollmentVO>().ok(enrollmentService.create(dto));
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询声纹采集任务")
    @RequiresPermissions("sys:role:normal")
    public Result<AgentVoicePrintEnrollmentVO> get(@PathVariable String id) {
        requireAgentRead(enrollmentService.getAgentIdByEnrollmentId(id));
        return new Result<AgentVoicePrintEnrollmentVO>().ok(enrollmentService.get(id));
    }

    @PostMapping("/{id}/samples")
    @Operation(summary = "追加声纹采集样本")
    @RequiresPermissions("sys:role:normal")
    public Result<AgentVoicePrintEnrollmentVO> addSample(
            @PathVariable String id,
            @RequestBody @Valid AgentVoicePrintEnrollmentSampleDTO dto) {
        requireAgentWrite(enrollmentService.getAgentIdByEnrollmentId(id));
        return new Result<AgentVoicePrintEnrollmentVO>().ok(enrollmentService.addSample(id, dto));
    }

    @PostMapping("/{id}/device-samples")
    @Operation(summary = "设备端上报声纹采集样本")
    public Result<AgentVoicePrintEnrollmentVO> addDeviceSample(
            @PathVariable String id,
            @RequestBody @Valid AgentVoicePrintEnrollmentSampleDTO dto) {
        return new Result<AgentVoicePrintEnrollmentVO>().ok(enrollmentService.addDeviceSample(id, dto));
    }

    @PostMapping("/{id}/complete")
    @Operation(summary = "完成声纹采集并注册声纹")
    @RequiresPermissions("sys:role:normal")
    public Result<AgentVoicePrintEnrollmentVO> complete(@PathVariable String id) {
        requireAgentWrite(enrollmentService.getAgentIdByEnrollmentId(id));
        return new Result<AgentVoicePrintEnrollmentVO>().ok(enrollmentService.complete(id));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "取消声纹采集任务")
    @RequiresPermissions("sys:role:normal")
    public Result<AgentVoicePrintEnrollmentVO> cancel(@PathVariable String id) {
        requireAgentWrite(enrollmentService.getAgentIdByEnrollmentId(id));
        return new Result<AgentVoicePrintEnrollmentVO>().ok(enrollmentService.cancel(id));
    }

    private void requireAgentRead(String agentId) {
        if (StringUtils.isBlank(agentId) || !agentService.checkAgentPermission(agentId, SecurityUser.getUserId())) {
            throw new RenException(ErrorCode.NO_PERMISSION);
        }
    }

    private void requireAgentWrite(String agentId) {
        if (StringUtils.isBlank(agentId)) {
            throw new RenException(ErrorCode.NO_PERMISSION);
        }
        AgentEntity agent = agentService.getAgentById(agentId);
        if (agent == null) {
            throw new RenException(ErrorCode.AGENT_NOT_FOUND);
        }
        workspacePermissionService.requireWrite(agent.getWorkspaceId());
    }
}

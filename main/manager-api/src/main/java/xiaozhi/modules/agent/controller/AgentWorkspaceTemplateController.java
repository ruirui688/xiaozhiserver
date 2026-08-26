package xiaozhi.modules.agent.controller;

import java.util.List;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.agent.dto.AgentTemplateApplyDTO;
import xiaozhi.modules.agent.dto.AgentTemplateCreateDTO;
import xiaozhi.modules.agent.dto.AgentTemplateSaveAsDTO;
import xiaozhi.modules.agent.dto.AgentTemplateReviewDTO;
import xiaozhi.modules.agent.service.AgentWorkspaceTemplateService;
import xiaozhi.modules.agent.vo.AgentTemplateApplyResultVO;
import xiaozhi.modules.agent.vo.AgentTemplateSaveResultVO;
import xiaozhi.modules.agent.vo.AgentTemplateSummaryVO;

@Tag(name = "工作空间角色模板")
@RestController
@RequiredArgsConstructor
@RequestMapping("/agent")
public class AgentWorkspaceTemplateController {
    private final AgentWorkspaceTemplateService workspaceTemplateService;

    @GetMapping("/templates")
    @Operation(summary = "获取系统模板和当前工作空间模板")
    @RequiresPermissions("sys:role:normal")
    public Result<List<AgentTemplateSummaryVO>> list() {
        return new Result<List<AgentTemplateSummaryVO>>().ok(workspaceTemplateService.listAccessible());
    }

    @PostMapping("/templates/{templateId}/create")
    @Operation(summary = "基于可访问模板原子创建智能体")
    @RequiresPermissions("sys:role:normal")
    public Result<AgentTemplateApplyResultVO> createFromTemplate(
            @PathVariable String templateId,
            @Valid @RequestBody AgentTemplateCreateDTO dto) {
        return new Result<AgentTemplateApplyResultVO>().ok(
                workspaceTemplateService.createFromTemplate(templateId, dto));
    }

    @PostMapping("/{agentId}/templates")
    @Operation(summary = "将当前角色草稿另存为工作空间模板")
    @RequiresPermissions("sys:role:normal")
    public Result<AgentTemplateSaveResultVO> saveAs(
            @PathVariable String agentId,
            @Valid @RequestBody AgentTemplateSaveAsDTO dto) {
        return new Result<AgentTemplateSaveResultVO>().ok(workspaceTemplateService.saveAs(agentId, dto));
    }

    @PostMapping("/{agentId}/templates/{templateId}/apply")
    @Operation(summary = "将模板立即应用到角色")
    @RequiresPermissions("sys:role:normal")
    public Result<AgentTemplateApplyResultVO> apply(
            @PathVariable String agentId,
            @PathVariable String templateId,
            @Valid @RequestBody AgentTemplateApplyDTO dto) {
        return new Result<AgentTemplateApplyResultVO>().ok(
                workspaceTemplateService.apply(agentId, templateId, dto));
    }

    @DeleteMapping("/templates/{templateId}")
    @Operation(summary = "删除当前工作空间模板")
    @RequiresPermissions("sys:role:normal")
    public Result<Void> delete(@PathVariable String templateId) {
        workspaceTemplateService.delete(templateId);
        return new Result<Void>().ok(null);
    }

    @PostMapping("/templates/{templateId}/submit-review")
    @Operation(summary = "提交模板中心审核")
    @RequiresPermissions("sys:role:normal")
    public Result<AgentTemplateSummaryVO> submitReview(@PathVariable String templateId) {
        return new Result<AgentTemplateSummaryVO>().ok(workspaceTemplateService.submitForReview(templateId));
    }

    @GetMapping("/templates/review-queue")
    @Operation(summary = "获取模板中心待审核队列")
    @RequiresPermissions("sys:role:normal")
    public Result<List<AgentTemplateSummaryVO>> reviewQueue() {
        return new Result<List<AgentTemplateSummaryVO>>().ok(workspaceTemplateService.listReviewQueue());
    }

    @PostMapping("/templates/{templateId}/review")
    @Operation(summary = "审核用户提交的模板")
    @RequiresPermissions("sys:role:normal")
    public Result<AgentTemplateSummaryVO> review(
            @PathVariable String templateId, @Valid @RequestBody AgentTemplateReviewDTO dto) {
        return new Result<AgentTemplateSummaryVO>().ok(workspaceTemplateService.review(templateId, dto));
    }

    @PostMapping("/templates/{templateId}/publish-official")
    @Operation(summary = "发布官方模板")
    @RequiresPermissions("sys:role:normal")
    public Result<AgentTemplateSummaryVO> publishOfficial(@PathVariable String templateId) {
        return new Result<AgentTemplateSummaryVO>().ok(workspaceTemplateService.publishOfficial(templateId));
    }
}

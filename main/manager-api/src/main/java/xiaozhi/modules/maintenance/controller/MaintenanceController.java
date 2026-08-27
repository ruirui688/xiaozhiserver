package xiaozhi.modules.maintenance.controller;

import java.util.List;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.maintenance.dto.MaintenancePolicyRequest;
import xiaozhi.modules.maintenance.dto.MaintenanceConversationRequest;
import xiaozhi.modules.maintenance.dto.MaintenanceConversationUpdateRequest;
import xiaozhi.modules.maintenance.dto.MaintenanceRunRequest;
import xiaozhi.modules.maintenance.entity.MaintenanceConversationEntity;
import xiaozhi.modules.maintenance.entity.MaintenanceMessageEntity;
import xiaozhi.modules.maintenance.entity.MaintenancePolicyEntity;
import xiaozhi.modules.maintenance.entity.MaintenanceProposalEntity;
import xiaozhi.modules.maintenance.entity.MaintenanceRunEntity;
import xiaozhi.modules.maintenance.entity.MaintenanceRunEventEntity;
import xiaozhi.modules.maintenance.service.MaintenanceResourceAccessService;
import xiaozhi.modules.maintenance.service.MaintenanceRunEventService;
import xiaozhi.modules.maintenance.service.MaintenanceService;

@RestController
@RequestMapping("/maintenance/{resource_type}/{resource_id}")
@Tag(name = "按资源启用的自主维护")
@RequiresPermissions("sys:role:normal")
public class MaintenanceController {
    private final MaintenanceService maintenanceService;
    private final MaintenanceResourceAccessService accessService;
    private final MaintenanceRunEventService eventService;

    public MaintenanceController(MaintenanceService maintenanceService,
            MaintenanceResourceAccessService accessService, MaintenanceRunEventService eventService) {
        this.maintenanceService = maintenanceService;
        this.accessService = accessService;
        this.eventService = eventService;
    }

    @GetMapping("/policy")
    public Result<MaintenancePolicyEntity> policy(@PathVariable("resource_type") String type,
            @PathVariable("resource_id") String id) {
        accessService.requireRead(type, id);
        return new Result<MaintenancePolicyEntity>().ok(maintenanceService.getPolicy(type, id));
    }

    @PutMapping("/policy")
    public Result<MaintenancePolicyEntity> savePolicy(@PathVariable("resource_type") String type,
            @PathVariable("resource_id") String id, @Valid @RequestBody MaintenancePolicyRequest request) {
        String workspaceId = accessService.requireWrite(type, id);
        return new Result<MaintenancePolicyEntity>().ok(maintenanceService.savePolicy(type, id, workspaceId, request));
    }

    @PostMapping("/runs")
    public Result<MaintenanceRunEntity> run(@PathVariable("resource_type") String type,
            @PathVariable("resource_id") String id,
            @Valid @RequestBody(required = false) MaintenanceRunRequest request) {
        accessService.requireWrite(type, id);
        String instruction = request == null ? null : request.getInstruction();
        return new Result<MaintenanceRunEntity>().ok(maintenanceService.trigger(type, id, "MANUAL", instruction));
    }

    @GetMapping("/runs")
    public Result<List<MaintenanceRunEntity>> runs(@PathVariable("resource_type") String type,
            @PathVariable("resource_id") String id) {
        accessService.requireRead(type, id);
        return new Result<List<MaintenanceRunEntity>>().ok(maintenanceService.listRuns(type, id));
    }

    @GetMapping("/conversations")
    public Result<List<MaintenanceConversationEntity>> conversations(@PathVariable("resource_type") String type,
            @PathVariable("resource_id") String id,
            @RequestParam(name = "include_archived", defaultValue = "false") boolean includeArchived) {
        accessService.requireRead(type, id);
        return new Result<List<MaintenanceConversationEntity>>()
                .ok(maintenanceService.listConversations(type, id, includeArchived));
    }

    @PostMapping("/conversations")
    public Result<MaintenanceConversationEntity> createConversation(@PathVariable("resource_type") String type,
            @PathVariable("resource_id") String id,
            @Valid @RequestBody MaintenanceConversationRequest request) {
        accessService.requireWrite(type, id);
        return new Result<MaintenanceConversationEntity>()
                .ok(maintenanceService.createConversation(type, id, request.getTitle()));
    }

    @PutMapping("/conversations/{conversation_id}")
    public Result<MaintenanceConversationEntity> updateConversation(@PathVariable("resource_type") String type,
            @PathVariable("resource_id") String id, @PathVariable("conversation_id") String conversationId,
            @Valid @RequestBody MaintenanceConversationUpdateRequest request) {
        accessService.requireWrite(type, id);
        MaintenanceConversationEntity conversation = maintenanceService.requireConversation(type, id, conversationId);
        return new Result<MaintenanceConversationEntity>()
                .ok(maintenanceService.updateConversation(conversation, request));
    }

    @DeleteMapping("/conversations/{conversation_id}")
    public Result<Void> deleteConversation(@PathVariable("resource_type") String type,
            @PathVariable("resource_id") String id, @PathVariable("conversation_id") String conversationId) {
        accessService.requireWrite(type, id);
        MaintenanceConversationEntity conversation = maintenanceService.requireConversation(type, id, conversationId);
        maintenanceService.deleteConversation(conversation);
        return new Result<Void>().ok(null);
    }

    @GetMapping("/conversations/{conversation_id}/messages")
    public Result<List<MaintenanceMessageEntity>> messages(@PathVariable("resource_type") String type,
            @PathVariable("resource_id") String id, @PathVariable("conversation_id") String conversationId) {
        accessService.requireRead(type, id);
        maintenanceService.requireConversation(type, id, conversationId);
        return new Result<List<MaintenanceMessageEntity>>().ok(maintenanceService.listMessages(conversationId));
    }

    @GetMapping("/conversations/{conversation_id}/runs")
    public Result<List<MaintenanceRunEntity>> conversationRuns(@PathVariable("resource_type") String type,
            @PathVariable("resource_id") String id, @PathVariable("conversation_id") String conversationId) {
        accessService.requireRead(type, id);
        maintenanceService.requireConversation(type, id, conversationId);
        return new Result<List<MaintenanceRunEntity>>().ok(maintenanceService.listConversationRuns(conversationId));
    }

    @PostMapping("/conversations/{conversation_id}/runs")
    public Result<MaintenanceRunEntity> conversationRun(@PathVariable("resource_type") String type,
            @PathVariable("resource_id") String id, @PathVariable("conversation_id") String conversationId,
            @Valid @RequestBody MaintenanceRunRequest request) {
        accessService.requireWrite(type, id);
        maintenanceService.requireConversation(type, id, conversationId);
        return new Result<MaintenanceRunEntity>().ok(
                maintenanceService.trigger(type, id, "MANUAL", request.getInstruction(), conversationId));
    }

    @GetMapping("/conversations/{conversation_id}/events")
    public Result<List<MaintenanceRunEventEntity>> conversationEvents(@PathVariable("resource_type") String type,
            @PathVariable("resource_id") String id, @PathVariable("conversation_id") String conversationId,
            @RequestParam(name = "after_id", defaultValue = "0") Long afterId) {
        accessService.requireRead(type, id);
        maintenanceService.requireConversation(type, id, conversationId);
        return new Result<List<MaintenanceRunEventEntity>>().ok(eventService.listConversation(conversationId, afterId));
    }

    @GetMapping("/conversations/{conversation_id}/proposals")
    public Result<List<MaintenanceProposalEntity>> conversationProposals(@PathVariable("resource_type") String type,
            @PathVariable("resource_id") String id, @PathVariable("conversation_id") String conversationId) {
        accessService.requireRead(type, id);
        maintenanceService.requireConversation(type, id, conversationId);
        return new Result<List<MaintenanceProposalEntity>>()
                .ok(maintenanceService.listConversationProposals(conversationId));
    }

    @GetMapping("/runs/{run_id}/events")
    public Result<List<MaintenanceRunEventEntity>> runEvents(@PathVariable("resource_type") String type,
            @PathVariable("resource_id") String id, @PathVariable("run_id") String runId,
            @RequestParam(name = "after_id", required = false, defaultValue = "0") Long afterId) {
        accessService.requireRead(type, id);
        MaintenanceRunEntity run = maintenanceService.getRun(runId);
        if (!type.equalsIgnoreCase(run.getResourceType()) || !id.equals(run.getResourceId())) {
            throw new xiaozhi.common.exception.RenException("维护运行记录不属于当前资源");
        }
        return new Result<List<MaintenanceRunEventEntity>>().ok(eventService.list(runId, afterId));
    }

    @GetMapping("/proposals")
    public Result<List<MaintenanceProposalEntity>> proposals(@PathVariable("resource_type") String type,
            @PathVariable("resource_id") String id) {
        accessService.requireRead(type, id);
        return new Result<List<MaintenanceProposalEntity>>().ok(maintenanceService.listProposals(type, id));
    }

    @PostMapping("/proposals/{proposal_id}/approve")
    public Result<MaintenanceProposalEntity> approve(@PathVariable("resource_type") String type,
            @PathVariable("resource_id") String id, @PathVariable("proposal_id") String proposalId) {
        accessService.requireWrite(type, id);
        MaintenanceProposalEntity proposal = maintenanceService.getProposal(proposalId);
        requireSameResource(type, id, proposal);
        return new Result<MaintenanceProposalEntity>().ok(maintenanceService.approve(proposalId));
    }

    @PostMapping("/proposals/{proposal_id}/reject")
    public Result<MaintenanceProposalEntity> reject(@PathVariable("resource_type") String type,
            @PathVariable("resource_id") String id, @PathVariable("proposal_id") String proposalId) {
        accessService.requireWrite(type, id);
        MaintenanceProposalEntity proposal = maintenanceService.getProposal(proposalId);
        requireSameResource(type, id, proposal);
        return new Result<MaintenanceProposalEntity>().ok(maintenanceService.reject(proposalId));
    }

    private void requireSameResource(String type, String id, MaintenanceProposalEntity proposal) {
        if (!type.equalsIgnoreCase(proposal.getResourceType()) || !id.equals(proposal.getResourceId())) {
            throw new xiaozhi.common.exception.RenException("维护提案不属于当前资源");
        }
    }
}

package xiaozhi.modules.maintenance.controller;

import java.util.List;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.maintenance.dto.MaintenancePolicyRequest;
import xiaozhi.modules.maintenance.entity.MaintenancePolicyEntity;
import xiaozhi.modules.maintenance.entity.MaintenanceProposalEntity;
import xiaozhi.modules.maintenance.entity.MaintenanceRunEntity;
import xiaozhi.modules.maintenance.service.MaintenanceResourceAccessService;
import xiaozhi.modules.maintenance.service.MaintenanceService;

@RestController
@AllArgsConstructor
@RequestMapping("/maintenance/{resource_type}/{resource_id}")
@Tag(name = "按资源启用的自主维护")
@RequiresPermissions("sys:role:normal")
public class MaintenanceController {
    private final MaintenanceService maintenanceService;
    private final MaintenanceResourceAccessService accessService;

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
            @PathVariable("resource_id") String id) {
        accessService.requireWrite(type, id);
        return new Result<MaintenanceRunEntity>().ok(maintenanceService.trigger(type, id, "MANUAL"));
    }

    @GetMapping("/runs")
    public Result<List<MaintenanceRunEntity>> runs(@PathVariable("resource_type") String type,
            @PathVariable("resource_id") String id) {
        accessService.requireRead(type, id);
        return new Result<List<MaintenanceRunEntity>>().ok(maintenanceService.listRuns(type, id));
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

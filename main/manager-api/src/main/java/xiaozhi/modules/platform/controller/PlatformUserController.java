package xiaozhi.modules.platform.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.platform.dto.PlatformRequests.*;
import xiaozhi.modules.platform.service.*;
import xiaozhi.modules.security.ops.OpsAccess;
import xiaozhi.modules.security.user.SecurityUser;

@RestController @RequiredArgsConstructor @RequestMapping("/user/platform")
public class PlatformUserController {
    private final PlatformAccountService accounts;
    private final PlatformPageService pages;
    private final PlatformResponsibilityService responsibility;
    private final AllowanceRequestService allowanceRequests;
    private final PlatformTrialGrantService trials;
    private long user() {OpsAccess.requireUser();return SecurityUser.getUserId();}
    @GetMapping("/account") public Result<?> account(){return new Result<>().ok(accounts.account(user()));}
    @GetMapping("/ledger") public Result<?> ledger(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int limit){long id=user();return new Result<>().ok(pages.read("ai_platform_ledger","id,entry_type AS type,amount,balance_after AS balanceAfter,workspace_id AS workspaceId,reservation_id AS reservationId,reason,created_at AS createdAt"," WHERE user_id=?","id DESC",page,limit,id));}
    @GetMapping({"/summary","/workspace-limits"}) public Result<?> summary(@RequestParam String workspaceId){return new Result<>().ok(responsibility.summary(workspaceId,user()));}
    @PutMapping("/workspace-limits") public Result<?> limits(@Valid @RequestBody WorkspaceLimits input){return new Result<>().ok(responsibility.limits(input,user()));}
    /** Users cannot top up; this is the only self-service path to more credit. */
    @GetMapping("/allowance-requests") public Result<?> allowanceRequests(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int limit){return new Result<>().ok(allowanceRequests.mine(user(),page,limit));}
    @PostMapping("/allowance-requests") public Result<?> requestAllowance(@Valid @RequestBody AllowanceRequest input){return new Result<>().ok(allowanceRequests.create(user(),new AllowanceRequestService.Create(input.requestId(),input.amount(),input.reason(),input.workspaceId())));}
    @GetMapping("/trial") public Result<?> trial(){return new Result<>().ok(trials.status(user()));}
}

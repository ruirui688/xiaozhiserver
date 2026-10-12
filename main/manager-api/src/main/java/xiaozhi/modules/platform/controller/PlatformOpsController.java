package xiaozhi.modules.platform.controller;

import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.platform.dto.PlatformRequests.*;
import xiaozhi.modules.platform.service.*;
import xiaozhi.modules.security.ops.OpsAccess;

import static xiaozhi.modules.platform.service.PlatformSupport.required;

@RestController @RequiredArgsConstructor @RequestMapping("/ops/platform")
public class PlatformOpsController {
    private final PlatformAccountService accounts;
    private final PlatformOpsMutationService mutations;
    private final PlatformResolutionService resolutions;
    private final PlatformCatalogPublicationService catalog;
    private final PlatformPageService pages;
    private final PlatformCredentialService credentials;
    private final PlatformCredentialInventoryService credentialInventory;
    private final PlatformTrialGrantService trials;
    private final AllowanceRequestService allowanceRequests;
    private final PlatformRateService rates;
    private final PlatformServiceAccountService services;
    private void platform(String permission) {OpsAccess.require(permission,"PLATFORM","platform-services");}
    private void user(String permission,long userId) {
        if(OpsAccess.has(permission,"PLATFORM","platform-services"))return;
        OpsAccess.require(permission,"USER",Long.toString(userId));
    }
    private String operator() {return OpsAccess.employee().getEmployeeId();}
    @GetMapping("/pool") public Result<?> pool(){platform("pool.read");return new Result<>().ok(accounts.pool());}
    @PostMapping("/pool") public Result<?> pool(@Valid @RequestBody PoolAdjustment input){platform("pool.adjust");return new Result<>().ok(accounts.adjust(input,operator()));}
    @GetMapping("/accounts") public Result<?> accounts(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int limit){platform("allowance.read");return new Result<>().ok(pages.read("ai_platform_account","user_id AS userId,granted,spent,reserved,(granted-spent-reserved) AS available,frozen,CASE WHEN frozen=1 THEN 'FROZEN' ELSE 'ACTIVE' END AS status,'CNY' AS currency"," WHERE user_id>0","user_id",page,limit));}
    @PostMapping("/allowance/grant") public Result<?> grant(@Valid @RequestBody Grant input){user("allowance.grant",input.userId());return new Result<>().ok(accounts.grant(input,operator()));}
    @PostMapping("/allowance/freeze") public Result<?> freeze(@Valid @RequestBody Freeze input){user("allowance.freeze",input.userId());return new Result<>().ok(accounts.freeze(input,operator()));}
    @GetMapping("/credentials") public Result<?> credentials(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int limit){platform("credentials.read");return new Result<>().ok(pages.read("ai_platform_credential","id,provider,upstream_id AS upstreamId,user_id AS userId,version,status,'***' AS masked,created_at AS createdAt","","created_at DESC,id",page,limit));}
    @PostMapping("/credentials") public Result<?> add(@Valid @RequestBody CredentialInput input){platform("credentials.write");return new Result<>().ok(mutations.perform("credentials.write",input.provider(),()->credentials.add(input)));}
    @PostMapping("/credentials/assign") public Result<?> assign(@Valid @RequestBody Assignment input){user("credentials.assign",input.userId());return new Result<>().ok(mutations.perform("credentials.assign",input.userId().toString(),()->credentials.assign(input,false)));}
    @PostMapping("/credentials/rotate") public Result<?> rotate(@Valid @RequestBody Assignment input){user("credentials.rotate",input.userId());return new Result<>().ok(mutations.perform("credentials.rotate",input.userId().toString(),()->credentials.assign(input,true)));}
    @PostMapping("/credentials/{id}/revoke") public Result<?> revoke(@PathVariable String id,@Valid @RequestBody Reason input){platform("credentials.revoke");return new Result<>().ok(mutations.perform("credentials.revoke",id,()->{credentials.revoke(id);return null;}));}
    @GetMapping("/credentials/inventory") public Result<?> inventory(){platform("credentials.read");return new Result<>().ok(credentialInventory.summary());}
    @GetMapping("/credentials/pending") public Result<?> pending(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int limit){platform("credentials.read");return new Result<>().ok(credentialInventory.pending(page,limit));}
    @GetMapping("/trial") public Result<?> trial(){platform("allowance.read");return new Result<>().ok(trials.summary());}
    @GetMapping("/allowance/requests") public Result<?> allowanceRequests(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int limit){if(!OpsAccess.has("allowance.read","PLATFORM","platform-services"))platform("allowance.review");return new Result<>().ok(allowanceRequests.pending(page,limit));}
    @PostMapping("/allowance/requests/{id}/approve") public Result<?> approveAllowance(@PathVariable String id,@Valid @RequestBody AllowanceDecision input){
        platform("allowance.grant");
        return new Result<>().ok(allowanceRequests.approve(id,operator(),new AllowanceRequestService.Decision(input.requestId(),input.amount(),input.reason())));
    }
    @PostMapping("/allowance/requests/{id}/reject") public Result<?> rejectAllowance(@PathVariable String id,@Valid @RequestBody Reason input){platform("allowance.review");return new Result<>().ok(allowanceRequests.reject(id,operator(),input.reason()));}
    @GetMapping("/rates") public Result<?> rates(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int limit){platform("rates.read");return new Result<>().ok(pages.read("ai_platform_rate","model_id AS modelId,version,metric_rates_json AS metricRatesJson,max_usage_json AS maxUsageJson,created_at AS createdAt","","id DESC",page,limit));}
    @PostMapping("/rates") public Result<?> rate(@Valid @RequestBody Rate input){platform("rates.write");return new Result<>().ok(mutations.perform("rates.write",input.modelId(),()->rates.add(input)));}
    @GetMapping("/services") public Result<?> services(){
        boolean money=OpsAccess.has("allowance.read","PLATFORM","platform-services")||OpsAccess.has("pool.read","PLATFORM","platform-services");
        if(!money)platform("credentials.read");
        var rows=services.list();
        if(!money)rows.forEach(row->{for(String key:java.util.List.of("granted","spent","reserved","available"))row.remove(key);});
        return new Result<>().ok(PlatformSupport.publicValue(rows));
    }
    @PostMapping("/services") public Result<?> service(@Valid @RequestBody PlatformServiceAccountService.ServiceInput input){platform("pool.adjust");return new Result<>().ok(mutations.perform("services.create",input.id(),()->services.create(input)));}
    @PostMapping("/services/grant") public Result<?> serviceGrant(@Valid @RequestBody ServiceGrant input){platform("allowance.grant");if(input.accountId()>=0)throw new xiaozhi.common.exception.RenException("PLATFORM_SERVICE_REQUIRED");return new Result<>().ok(accounts.grant(new Grant(input.requestId(),input.accountId(),input.amount(),input.reason()),operator()));}
    @GetMapping("/catalog/{id}/publication") public Result<?> publication(@PathVariable String id){return new Result<>().ok(catalog.preview(id));}
    @PostMapping("/catalog/{id}/publish") public Result<?> publish(@PathVariable String id){return new Result<>().ok(catalog.publish(id));}
    @GetMapping("/reservations/review") public Result<?> review(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int limit){platform("allowance.review");return new Result<>().ok(pages.read("ai_platform_reservation","id,model_id AS modelId,payer_user_id AS payerUserId,workspace_id AS workspaceId,service_type AS serviceType,reserved_amount AS reservedAmount,provider_request_id AS providerRequestId,created_at AS createdAt"," WHERE state='UNKNOWN'","created_at,id",page,limit));}
    @PostMapping("/reservations/{id}/resolve") public Result<?> resolve(@PathVariable String id,@Valid @RequestBody PlatformResolutionService.Resolution input){platform("allowance.review");return new Result<>().ok(resolutions.resolve(id,input,operator()));}
    public record ServiceGrant(String requestId,long accountId,String amount,String reason){}
}

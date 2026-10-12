package xiaozhi.modules.billing.controller;

import lombok.RequiredArgsConstructor;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.*;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.billing.service.BillingBenefitService;
import xiaozhi.modules.billing.service.BillingAccountService;
import xiaozhi.common.exception.RenException;

@xiaozhi.modules.security.ops.SharedOfficialEndpoint
@RestController @RequestMapping("/billing/benefits") @RequiredArgsConstructor
@RequiresPermissions("supplier.bill.read")
public class BillingBenefitController {
    private final BillingBenefitService service;
    private final BillingAccountService accounts;
    @GetMapping public Result<Object> overview(@RequestParam(defaultValue = "false") boolean accountView,
            @RequestParam(required=false) String provider,@RequestParam(required=false) String accountRef) {
        if(accountRef!=null || provider!=null) {
            if(!accountView)throw new RenException("工作空间权益不支持切换供应商账号");
            accounts.requireAccount(provider,accountRef);
        }
        return new Result<>().ok(BillingController.present(service.overview(true,provider,accountRef)));
    }
    @PostMapping("/sync") @RequiresPermissions("supplier.bill.sync")
    public Result<Object> sync() { return new Result<>().ok(BillingController.present(service.sync())); }
}

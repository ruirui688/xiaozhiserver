package xiaozhi.modules.billing.controller;

import lombok.RequiredArgsConstructor;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.*;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.billing.dto.BillingAccountFilter;
import xiaozhi.modules.billing.service.BillingAccountService;

@RestController @RequestMapping("/billing") @RequiredArgsConstructor
@RequiresPermissions("sys:role:superAdmin")
public class BillingAccountController {
    private final BillingAccountService accounts;
    @GetMapping("/accounts") public Result<Object> accounts() { return ok(accounts.accounts()); }
    @GetMapping("/account-overview") public Result<Object> overview(BillingAccountFilter filter) { return ok(accounts.overview(filter)); }
    @GetMapping("/account-bills") public Result<Object> bills(BillingAccountFilter filter) { return ok(accounts.bills(filter)); }
    @GetMapping("/sync-status") public Result<Object> status(BillingAccountFilter filter) { return ok(accounts.status(filter)); }
    private Result<Object> ok(Object value) { return new Result<>().ok(BillingController.present(value)); }
}

package xiaozhi.modules.billing.controller;

import lombok.RequiredArgsConstructor;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.*;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.billing.service.BillingBenefitService;

@RestController @RequestMapping("/billing/benefits") @RequiredArgsConstructor
public class BillingBenefitController {
    private final BillingBenefitService service;
    @GetMapping public Result<Object> overview(@RequestParam(defaultValue = "false") boolean accountView) {
        return new Result<>().ok(BillingController.present(service.overview(accountView)));
    }
    @PostMapping("/sync") @RequiresPermissions("sys:role:superAdmin")
    public Result<Object> sync() { return new Result<>().ok(BillingController.present(service.sync())); }
}

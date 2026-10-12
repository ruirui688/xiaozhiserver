package xiaozhi.modules.billing.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.billing.dto.BillingCollectorHealthRequest;
import xiaozhi.modules.billing.service.BillingCollectionHealthService;

@xiaozhi.modules.security.ops.SharedOfficialEndpoint
@RestController @RequiredArgsConstructor
public class BillingCollectionHealthController {
    private final BillingCollectionHealthService health;
    @PostMapping("/config/billing/health")
    public Result<Object> heartbeat(@Valid @RequestBody BillingCollectorHealthRequest request) { return new Result<>().ok(BillingController.present(health.accept(request))); }
    @GetMapping("/billing/collection-health")
    public Result<Object> overview(@RequestParam(defaultValue="false") boolean accountView) { return new Result<>().ok(BillingController.present(health.overview(accountView))); }
}

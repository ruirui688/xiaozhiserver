package xiaozhi.modules.billing.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.billing.dto.UsageBatchRequest;
import xiaozhi.modules.billing.service.BillingUsageService;

/** Shared-secret filter plus immutable per-agent/device capability checks in the service. */
@RestController @RequestMapping("/config/billing") @RequiredArgsConstructor
public class BillingUsageController {
    private final BillingUsageService usage;
    @PostMapping("/usage") public Result<Object> usage(@Valid @RequestBody UsageBatchRequest request) {
        return new Result<>().ok(usage.accept(request));
    }
}

package xiaozhi.modules.platform.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.billing.dto.BillingFilter;
import xiaozhi.modules.platform.service.PlatformUserUsageService;
import xiaozhi.modules.security.ops.OpsAccess;

/** USER authentication is declared by /user/platform/** in ShiroConfig; workspace access is checked by the service. */
@RestController @RequiredArgsConstructor @RequestMapping("/user/platform/usage")
public class PlatformUserUsageController {
    private final PlatformUserUsageService usage;
    @GetMapping("/options") public Result<?> options(){OpsAccess.requireUser();return new Result<>().ok(usage.options());}
    @GetMapping("/overview") public Result<?> overview(BillingFilter filter){OpsAccess.requireUser();return new Result<>().ok(usage.overview(filter));}
    @GetMapping("/groups") public Result<?> groups(BillingFilter filter,@RequestParam String groupBy){OpsAccess.requireUser();return new Result<>().ok(usage.groups(filter,groupBy));}
    @GetMapping("/events") public Result<?> events(BillingFilter filter){OpsAccess.requireUser();return new Result<>().ok(usage.events(filter));}
}

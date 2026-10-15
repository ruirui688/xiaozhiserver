package xiaozhi.modules.platform.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.platform.service.PlatformOpsMutationService;
import xiaozhi.modules.platform.service.PlatformTrialGrantService;
import xiaozhi.modules.platform.service.PlatformSupport;
import xiaozhi.modules.security.ops.OpsAccess;

/** Uses the existing protected OPS route; no anonymous or user claim endpoint. */
@RestController @RequiredArgsConstructor @RequestMapping("/ops/platform/trial")
public class PlatformTrialOpsController {
    private final PlatformTrialGrantService trials;
    private final PlatformOpsMutationService mutations;

    @GetMapping("/requests")
    public Result<?> requests(@RequestParam(defaultValue="REVIEW") String status,
            @RequestParam(defaultValue="0") long afterUserId, @RequestParam(defaultValue="20") int limit) {
        OpsAccess.require("allowance.read", "PLATFORM", "platform-services");
        return new Result<>().ok(trials.requests(status, afterUserId, limit));
    }

    @PostMapping("/retry")
    public Result<?> retry(@Valid @RequestBody Retry input) {
        return new Result<>().ok(mutations.perform("trial.retry", "batch:" + PlatformSupport.hash(input.userIds().toString()),
                () -> trials.retry(input.userIds(), input.reason())));
    }

    public record Retry(@NotEmpty @Size(max=100) List<@Positive Long> userIds,
                        @NotBlank @Size(max=500) String reason) {}
}

package xiaozhi.modules.platform.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.platform.dto.PlatformRequests.*;
import xiaozhi.modules.platform.service.PlatformAdmissionService;

/** ServerSecretFilter restricts these capabilities to trusted execution servers. */
@RestController @RequiredArgsConstructor @RequestMapping("/config/platform")
public class PlatformExecutionController {
    private final PlatformAdmissionService admission;
    @PostMapping("/admit") public Result<?> admit(@Valid @RequestBody Admit input){return new Result<>().ok(admission.admit(input));}
    @PostMapping("/start") public Result<?> start(@Valid @RequestBody Permit input){return new Result<>().ok(admission.start(input));}
    @PostMapping("/extend") public Result<?> extend(@Valid @RequestBody Extend input){return new Result<>().ok(admission.extend(input));}
    @PostMapping("/settle") public Result<?> settle(@Valid @RequestBody Settle input){return new Result<>().ok(admission.settle(input));}
    @PostMapping("/release") public Result<?> release(@Valid @RequestBody Permit input){return new Result<>().ok(admission.release(input));}
}

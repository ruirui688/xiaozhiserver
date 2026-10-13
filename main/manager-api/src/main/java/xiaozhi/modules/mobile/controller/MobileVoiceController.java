package xiaozhi.modules.mobile.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.*;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.mobile.service.MobileVoiceSessionService;
import xiaozhi.modules.security.ops.OpsAccess;
import xiaozhi.modules.security.user.SecurityUser;

@RestController
@RequiredArgsConstructor
@RequestMapping("/mobile")
public class MobileVoiceController {
    private final MobileVoiceSessionService sessions;

    @PostMapping("/voice-session")
    @RequiresPermissions("sys:role:normal")
    public Result<MobileVoiceSessionService.Ticket> issue(@Valid @RequestBody Request request) {
        OpsAccess.requireUser();
        return new Result<MobileVoiceSessionService.Ticket>().ok(
                sessions.issue(request.agentId(), request.installationId(), SecurityUser.getUserId()));
    }

    public record Request(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String agentId,
            @NotBlank @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") String installationId) {}
}

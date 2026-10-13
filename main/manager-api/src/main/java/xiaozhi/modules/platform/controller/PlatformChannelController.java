package xiaozhi.modules.platform.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.platform.service.PlatformChannelService;
import xiaozhi.modules.platform.service.PlatformOpsMutationService;
import xiaozhi.modules.security.ops.OpsAccess;

@RestController @RequiredArgsConstructor @RequestMapping("/ops/platform/channels")
public class PlatformChannelController {
    private final PlatformChannelService channels;
    private final PlatformOpsMutationService mutations;
    @GetMapping public Result<?> list(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int limit,
            @RequestParam(required=false) String provider,@RequestParam(required=false) String serviceType,@RequestParam(required=false) String keyword) {
        OpsAccess.require("credentials.read","PLATFORM","platform-services");return new Result<>().ok(channels.list(page,limit,provider,serviceType,keyword));
    }
    @GetMapping("/models") public Result<?> models(){
        if(!OpsAccess.has("credentials.read","PLATFORM","platform-services")&&!OpsAccess.has("rates.read","PLATFORM","platform-services"))OpsAccess.require("pool.adjust","PLATFORM","platform-services");
        return new Result<>().ok(channels.models());
    }
    @PostMapping public Result<?> save(@Valid @RequestBody PlatformChannelService.Input input){
        OpsAccess.require("credentials.write","PLATFORM","platform-services");
        return new Result<>().ok(mutations.perform("channels.write",input.modelId(),()->channels.save(input)));
    }
    /** Malformed JSON may contain supplier keys. Do not pass the parser exception into generic logging. */
    @ExceptionHandler(HttpMessageNotReadableException.class) public Result<?> malformed(){return new Result<>().error("INVALID_CHANNEL_INPUT");}
}

package xiaozhi.modules.config.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.device.dto.DeviceSceneSwitchResultDTO;
import xiaozhi.modules.device.service.DeviceSceneService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/config/device-scenes")
public class DeviceSceneRuntimeController {
    private final DeviceSceneService deviceSceneService;

    @PostMapping("/switch-result")
    public Result<Void> recordSwitchResult(@Valid @RequestBody DeviceSceneSwitchResultDTO dto) {
        deviceSceneService.recordRuntimeResult(dto);
        return new Result<>();
    }
}

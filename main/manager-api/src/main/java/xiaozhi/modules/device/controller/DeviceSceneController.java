package xiaozhi.modules.device.controller;

import java.util.List;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.device.dto.DeviceSceneCreateDTO;
import xiaozhi.modules.device.dto.DeviceSceneSwitchDTO;
import xiaozhi.modules.device.dto.DeviceSceneUpdateDTO;
import xiaozhi.modules.device.service.DeviceSceneService;
import xiaozhi.modules.device.vo.DeviceSceneVO;

@Tag(name = "设备场景管理")
@RestController
@RequiredArgsConstructor
@RequestMapping("/devices/{deviceId}")
public class DeviceSceneController {
    private final DeviceSceneService deviceSceneService;

    @GetMapping("/scenes")
    @Operation(summary = "获取设备场景")
    @RequiresPermissions("sys:role:normal")
    public Result<List<DeviceSceneVO>> list(@PathVariable String deviceId) {
        return new Result<List<DeviceSceneVO>>().ok(deviceSceneService.listScenes(deviceId));
    }

    @PostMapping("/scenes")
    @Operation(summary = "关联设备场景")
    @RequiresPermissions("sys:role:normal")
    public Result<DeviceSceneVO> create(
            @PathVariable String deviceId,
            @Valid @RequestBody DeviceSceneCreateDTO dto) {
        return new Result<DeviceSceneVO>().ok(deviceSceneService.createScene(deviceId, dto));
    }

    @PutMapping("/scenes/{bindingId}")
    @Operation(summary = "更新设备场景")
    @RequiresPermissions("sys:role:normal")
    public Result<DeviceSceneVO> update(
            @PathVariable String deviceId,
            @PathVariable String bindingId,
            @Valid @RequestBody DeviceSceneUpdateDTO dto) {
        return new Result<DeviceSceneVO>().ok(deviceSceneService.updateScene(deviceId, bindingId, dto));
    }

    @DeleteMapping("/scenes/{bindingId}")
    @Operation(summary = "删除设备场景")
    @RequiresPermissions("sys:role:normal")
    public Result<Void> delete(@PathVariable String deviceId, @PathVariable String bindingId) {
        deviceSceneService.deleteScene(deviceId, bindingId);
        return new Result<>();
    }

    @PutMapping("/active-scene")
    @Operation(summary = "切换当前设备场景")
    @RequiresPermissions("sys:role:normal")
    public Result<DeviceSceneVO> switchActive(
            @PathVariable String deviceId,
            @Valid @RequestBody DeviceSceneSwitchDTO dto) {
        return new Result<DeviceSceneVO>().ok(deviceSceneService.switchActiveScene(deviceId, dto));
    }
}

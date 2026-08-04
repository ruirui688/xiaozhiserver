package xiaozhi.modules.person.controller;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.person.dto.ServerFaceRecognitionRequestDTO;
import xiaozhi.modules.person.vo.ServerFaceRecognitionCapabilityVO;
import xiaozhi.modules.workspace.service.WorkspacePermissionService;

@Tag(name = "人脸识别能力")
@RestController
@RequiredArgsConstructor
@RequestMapping("/resource/person-recognition")
public class PersonRecognitionController {
    private final DeviceDao deviceDao;
    private final WorkspacePermissionService workspacePermissionService;

    @GetMapping("/capability")
    @Operation(summary = "查询服务端人脸识别能力")
    @RequiresPermissions("sys:role:normal")
    public Result<ServerFaceRecognitionCapabilityVO> capability() {
        workspacePermissionService.requireRead(workspacePermissionService.currentWorkspaceId());
        return new Result<ServerFaceRecognitionCapabilityVO>().ok(unsupported());
    }

    @PostMapping(value = "/server", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "服务端人脸识别预留接口（当前不执行识别）")
    @RequiresPermissions("sys:role:normal")
    public Result<ServerFaceRecognitionCapabilityVO> recognizeOnServer(
            @Valid @RequestBody ServerFaceRecognitionRequestDTO dto) {
        DeviceEntity device = deviceDao.selectOne(new LambdaQueryWrapper<DeviceEntity>()
                .eq(DeviceEntity::getId, dto.getDeviceId())
                .last("LIMIT 1"));
        if (device == null) {
            throw new RenException("设备不存在");
        }
        workspacePermissionService.requireRead(device.getWorkspaceId());
        // 当前版本不接受图片字段，避免形成未经声明的服务端生物特征处理链路。
        return new Result<ServerFaceRecognitionCapabilityVO>().ok(unsupported());
    }

    private ServerFaceRecognitionCapabilityVO unsupported() {
        return ServerFaceRecognitionCapabilityVO.builder()
                .supported(false)
                .mode("DEVICE_LOCAL_ONLY")
                .message("当前仅支持设备本地识别；服务端识别接口已预留但默认关闭")
                .build();
    }
}

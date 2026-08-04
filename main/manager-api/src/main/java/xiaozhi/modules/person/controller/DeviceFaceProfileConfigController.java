package xiaozhi.modules.person.controller;

import java.time.Duration;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.person.dto.DeviceFaceRecognitionEventDTO;
import xiaozhi.modules.person.service.PersonProfileService;
import xiaozhi.modules.person.vo.DeviceFaceProfileManifestVO;
import xiaozhi.modules.person.vo.DeviceFaceRecognitionActionVO;

@Tag(name = "设备人脸资源配置")
@RestController
@RequiredArgsConstructor
@RequestMapping("/config/device-face-profiles")
public class DeviceFaceProfileConfigController {
    private final PersonProfileService personProfileService;

    @GetMapping("/{macAddress}")
    @Operation(summary = "服务端获取设备人脸资源清单")
    public Result<DeviceFaceProfileManifestVO> manifest(@PathVariable String macAddress) {
        return new Result<DeviceFaceProfileManifestVO>().ok(personProfileService.getDeviceManifest(macAddress));
    }

    @GetMapping("/{macAddress}/{personId}/image")
    @Operation(summary = "服务端读取已授权设备人脸图片")
    public ResponseEntity<org.springframework.core.io.Resource> image(
            @PathVariable String macAddress,
            @PathVariable String personId) {
        PersonProfileService.FaceImage face = personProfileService.readFaceForDevice(macAddress, personId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .eTag("\"" + face.sha256() + "\"")
                .contentType(MediaType.parseMediaType(face.contentType()))
                .body(face.resource());
    }

    @PostMapping("/recognition-events")
    @Operation(summary = "服务端接收设备本地人脸识别事件")
    public Result<DeviceFaceRecognitionActionVO> recognitionEvent(
            @Valid @RequestBody DeviceFaceRecognitionEventDTO dto) {
        return new Result<DeviceFaceRecognitionActionVO>().ok(personProfileService.recordRecognition(dto));
    }
}

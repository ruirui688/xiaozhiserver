package xiaozhi.modules.person.controller;

import java.util.List;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.agent.vo.AgentVoicePrintVO;
import xiaozhi.modules.person.dto.PersonProfileSaveDTO;
import xiaozhi.modules.person.service.PersonProfileService;
import xiaozhi.modules.person.vo.PersonAgentOptionVO;
import xiaozhi.modules.person.vo.PersonProfileVO;

@Tag(name = "资源中心人物档案")
@RestController
@RequiredArgsConstructor
@RequestMapping("/resource/persons")
public class PersonProfileController {
    private final PersonProfileService personProfileService;

    @GetMapping
    @Operation(summary = "获取当前工作空间人物档案")
    @RequiresPermissions("sys:role:normal")
    public Result<List<PersonProfileVO>> list(@RequestParam(required = false) String keyword) {
        return new Result<List<PersonProfileVO>>().ok(personProfileService.list(keyword));
    }

    @GetMapping("/agents")
    @Operation(summary = "获取可绑定人物人脸库的工作空间智能体")
    @RequiresPermissions("sys:role:normal")
    public Result<List<PersonAgentOptionVO>> agents() {
        return new Result<List<PersonAgentOptionVO>>().ok(personProfileService.listWorkspaceAgents());
    }

    @GetMapping("/{id}/voice-print-options")
    @Operation(summary = "获取人物可关联的声纹")
    @RequiresPermissions("sys:role:normal")
    public Result<List<AgentVoicePrintVO>> voicePrintOptions(@PathVariable String id) {
        return new Result<List<AgentVoicePrintVO>>().ok(personProfileService.listVoicePrintOptions(id));
    }

    @PostMapping
    @Operation(summary = "创建人物档案")
    @RequiresPermissions("sys:role:normal")
    public Result<PersonProfileVO> create(@Valid @RequestBody PersonProfileSaveDTO dto) {
        return new Result<PersonProfileVO>().ok(personProfileService.create(dto));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新人物档案")
    @RequiresPermissions("sys:role:normal")
    public Result<PersonProfileVO> update(
            @PathVariable String id,
            @Valid @RequestBody PersonProfileSaveDTO dto) {
        return new Result<PersonProfileVO>().ok(personProfileService.update(id, dto));
    }

    @PutMapping("/{id}/voice-print/{voicePrintId}")
    @Operation(summary = "关联人物与声纹")
    @RequiresPermissions("sys:role:normal")
    public Result<PersonProfileVO> bindVoicePrint(
            @PathVariable String id,
            @PathVariable String voicePrintId) {
        return new Result<PersonProfileVO>().ok(personProfileService.bindVoicePrint(id, voicePrintId));
    }

    @DeleteMapping("/{id}/voice-print")
    @Operation(summary = "解除人物与声纹关联")
    @RequiresPermissions("sys:role:normal")
    public Result<PersonProfileVO> unbindVoicePrint(@PathVariable String id) {
        return new Result<PersonProfileVO>().ok(personProfileService.unbindVoicePrint(id));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除人物档案")
    @RequiresPermissions("sys:role:normal")
    public Result<Void> delete(@PathVariable String id) {
        personProfileService.delete(id);
        return new Result<>();
    }

    @PostMapping(value = "/{id}/face", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "上传并归一化人物人脸照片")
    @RequiresPermissions("sys:role:normal")
    public Result<PersonProfileVO> uploadFace(
            @PathVariable String id,
            @RequestParam("file") MultipartFile file) {
        return new Result<PersonProfileVO>().ok(personProfileService.uploadFace(id, file));
    }

    @GetMapping("/{id}/face")
    @Operation(summary = "读取人物人脸照片")
    @RequiresPermissions("sys:role:normal")
    public ResponseEntity<org.springframework.core.io.Resource> face(@PathVariable String id) {
        PersonProfileService.FaceImage face = personProfileService.readFaceForManagement(id);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .eTag("\"" + face.sha256() + "\"")
                .contentType(MediaType.parseMediaType(face.contentType()))
                .body(face.resource());
    }

    @DeleteMapping("/{id}/face")
    @Operation(summary = "删除人物人脸照片")
    @RequiresPermissions("sys:role:normal")
    public Result<Void> deleteFace(@PathVariable String id) {
        personProfileService.deleteFace(id);
        return new Result<>();
    }

    @PostMapping("/{id}/publish")
    @Operation(summary = "显式发布人物变更到已绑定智能体人脸库")
    @RequiresPermissions("sys:role:normal")
    public Result<PersonProfileVO> publish(@PathVariable String id) {
        return new Result<PersonProfileVO>().ok(personProfileService.publish(id));
    }
}

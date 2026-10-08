package xiaozhi.modules.hotword;

import java.util.List;
import java.util.Map;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import xiaozhi.common.utils.Result;

@RestController
@RequiredArgsConstructor
@RequestMapping("/resource/asr-hotwords")
@RequiresPermissions("sys:role:normal")
public class HotwordController {
    private final HotwordService service;
    private final HotwordImportService imports;
    @GetMapping("/remote")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<List<Map<String, Object>>> remote(@RequestParam String modelId) { return new Result<List<Map<String, Object>>>().ok(imports.remote(modelId)); }
    @PostMapping("/import")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<Map<String, Object>> importRemote(@Valid @RequestBody HotwordImportDTO dto) { return new Result<Map<String, Object>>().ok(imports.importRemote(dto)); }
    @GetMapping
    public Result<List<Map<String, Object>>> list() { return new Result<List<Map<String, Object>>>().ok(service.list()); }
    @GetMapping("/models")
    public Result<List<Map<String, Object>>> models() { return new Result<List<Map<String, Object>>>().ok(service.modelOptions()); }
    @GetMapping("/agents")
    public Result<List<Map<String, Object>>> agents() { return new Result<List<Map<String, Object>>>().ok(service.agentOptions()); }
    @PostMapping
    public Result<Map<String, Object>> create(@Valid @RequestBody HotwordSaveDTO dto) { return new Result<Map<String, Object>>().ok(service.create(dto)); }
    @PutMapping("/{id}")
    public Result<Map<String, Object>> update(@PathVariable String id, @Valid @RequestBody HotwordSaveDTO dto) { return new Result<Map<String, Object>>().ok(service.update(id, dto)); }
    @PostMapping("/{id}/publish")
    public Result<Map<String, Object>> publish(@PathVariable String id) { return new Result<Map<String, Object>>().ok(service.publish(id)); }
    @PostMapping("/{id}/apply")
    public Result<Map<String, Object>> apply(@PathVariable String id, @Valid @RequestBody HotwordApplyDTO dto) { return new Result<Map<String, Object>>().ok(service.apply(id, dto.agentIds(), false)); }
    @PostMapping("/{id}/unapply")
    public Result<Map<String, Object>> unapply(@PathVariable String id, @Valid @RequestBody HotwordApplyDTO dto) { return new Result<Map<String, Object>>().ok(service.apply(id, dto.agentIds(), true)); }
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) { service.delete(id); return new Result<>(); }
}

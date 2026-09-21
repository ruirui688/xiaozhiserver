package xiaozhi.modules.knowledge.controller;

import java.util.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.*;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.knowledge.dto.KnowledgeEntryRequest;
import xiaozhi.modules.knowledge.entity.KnowledgeEntryRevisionEntity;
import xiaozhi.modules.knowledge.service.KnowledgeEntryService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/datasets/{datasetId}/documents/{documentId}/entries")
@RequiresPermissions("sys:role:normal")
public class KnowledgeEntryController {
    private final KnowledgeEntryService entries;

    @GetMapping
    public Result<Map<String, Object>> list(@PathVariable String datasetId, @PathVariable String documentId,
            @RequestParam(defaultValue = "1") int page, @RequestParam(name = "page_size", defaultValue = "10") int size,
            @RequestParam(required = false) String keywords) {
        return new Result<Map<String, Object>>().ok(entries.list(datasetId, documentId, page, size, keywords));
    }

    @PostMapping
    public Result<Map<String, Object>> create(@PathVariable String datasetId, @PathVariable String documentId,
            @Valid @RequestBody KnowledgeEntryRequest request) {
        return new Result<Map<String, Object>>().ok(entries.create(datasetId, documentId, request));
    }

    @PutMapping("/{entryId}")
    public Result<Map<String, Object>> update(@PathVariable String datasetId, @PathVariable String documentId,
            @PathVariable String entryId, @Valid @RequestBody KnowledgeEntryRequest request) {
        return new Result<Map<String, Object>>().ok(entries.update(datasetId, documentId, entryId, request));
    }

    @GetMapping("/{entryId}/revisions")
    public Result<List<KnowledgeEntryRevisionEntity>> history(@PathVariable String datasetId, @PathVariable String documentId,
            @PathVariable String entryId) {
        return new Result<List<KnowledgeEntryRevisionEntity>>().ok(entries.history(datasetId, documentId, entryId));
    }

    @PostMapping("/{entryId}/restore")
    public Result<Map<String, Object>> restore(@PathVariable String datasetId, @PathVariable String documentId,
            @PathVariable String entryId, @Valid @RequestBody KnowledgeEntryRequest.Restore request) {
        return new Result<Map<String, Object>>().ok(entries.restore(datasetId, documentId, entryId, request));
    }
}

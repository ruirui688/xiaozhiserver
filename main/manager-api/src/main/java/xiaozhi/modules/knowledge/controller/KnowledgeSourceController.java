package xiaozhi.modules.knowledge.controller;

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
import lombok.AllArgsConstructor;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.knowledge.dto.KnowledgeBaseDTO;
import xiaozhi.modules.knowledge.service.KnowledgeBaseService;
import xiaozhi.modules.knowledge.service.KnowledgeResourcePermissionService;
import xiaozhi.modules.knowledge.source.dto.KnowledgeSourceRequest;
import xiaozhi.modules.knowledge.source.dto.KnowledgeSourceView;
import xiaozhi.modules.knowledge.source.entity.KnowledgeSyncRunEntity;
import xiaozhi.modules.knowledge.source.service.KnowledgeSourceService;
import xiaozhi.modules.knowledge.source.service.KnowledgeSourceSyncService;

@RestController
@AllArgsConstructor
@RequestMapping("/datasets/{dataset_id}/sources")
@Tag(name = "知识库动态数据源")
public class KnowledgeSourceController {
    private final KnowledgeSourceService sourceService;
    private final KnowledgeSourceSyncService sourceSyncService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeResourcePermissionService knowledgePermissionService;

    @GetMapping
    @Operation(summary = "列出指定知识库的动态数据源")
    @RequiresPermissions("sys:role:normal")
    public Result<List<KnowledgeSourceView>> list(@PathVariable("dataset_id") String datasetId) {
        requireRead(datasetId);
        return new Result<List<KnowledgeSourceView>>().ok(sourceService.list(datasetId));
    }

    @PostMapping
    @Operation(summary = "为指定知识库新增动态数据源")
    @RequiresPermissions("sys:role:normal")
    public Result<KnowledgeSourceView> create(
            @PathVariable("dataset_id") String datasetId,
            @Valid @RequestBody KnowledgeSourceRequest request) {
        requireWrite(datasetId);
        return new Result<KnowledgeSourceView>().ok(sourceService.create(datasetId, request));
    }

    @PutMapping("/{source_id}")
    @Operation(summary = "更新指定知识库的数据源")
    @RequiresPermissions("sys:role:normal")
    public Result<KnowledgeSourceView> update(
            @PathVariable("dataset_id") String datasetId,
            @PathVariable("source_id") String sourceId,
            @Valid @RequestBody KnowledgeSourceRequest request) {
        requireWrite(datasetId);
        return new Result<KnowledgeSourceView>().ok(sourceService.update(datasetId, sourceId, request));
    }

    @DeleteMapping("/{source_id}")
    @Operation(summary = "删除动态数据源及其生成的版本")
    @RequiresPermissions("sys:role:normal")
    public Result<Void> delete(
            @PathVariable("dataset_id") String datasetId,
            @PathVariable("source_id") String sourceId) {
        requireWrite(datasetId);
        sourceService.delete(datasetId, sourceId);
        return new Result<>();
    }

    @PostMapping("/{source_id}/sync")
    @Operation(summary = "立即同步指定数据源")
    @RequiresPermissions("sys:role:normal")
    public Result<KnowledgeSourceView> sync(
            @PathVariable("dataset_id") String datasetId,
            @PathVariable("source_id") String sourceId) {
        requireWrite(datasetId);
        return new Result<KnowledgeSourceView>().ok(sourceSyncService.trigger(datasetId, sourceId, "MANUAL"));
    }

    @GetMapping("/{source_id}/runs")
    @Operation(summary = "查看数据源最近同步记录")
    @RequiresPermissions("sys:role:normal")
    public Result<List<KnowledgeSyncRunEntity>> runs(
            @PathVariable("dataset_id") String datasetId,
            @PathVariable("source_id") String sourceId) {
        requireRead(datasetId);
        return new Result<List<KnowledgeSyncRunEntity>>().ok(sourceService.listRuns(datasetId, sourceId));
    }

    private void requireRead(String datasetId) {
        KnowledgeBaseDTO dataset = knowledgeBaseService.getByDatasetId(datasetId);
        knowledgePermissionService.requireRead(dataset);
    }

    private void requireWrite(String datasetId) {
        KnowledgeBaseDTO dataset = knowledgeBaseService.getByDatasetId(datasetId);
        knowledgePermissionService.requireWrite(dataset);
    }
}

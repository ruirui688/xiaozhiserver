package xiaozhi.modules.model.controller;

import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import xiaozhi.common.page.PageData;
import xiaozhi.common.utils.Result;
import xiaozhi.common.utils.ResultUtils;
import xiaozhi.common.validator.group.UpdateGroup;
import xiaozhi.modules.model.dto.ModelProviderDTO;
import xiaozhi.modules.model.service.ModelProviderService;
import xiaozhi.modules.model.service.ModelProviderOperationsService;
import xiaozhi.modules.security.ops.OpsAccess;
import xiaozhi.modules.security.ops.SharedOfficialEndpoint;

@AllArgsConstructor
@RestController
@RequestMapping("/models/provider")
@Tag(name = "模型供应器")
public class ModelProviderController {

    private final ModelProviderService modelProviderService;
    private final ModelProviderOperationsService operations;

    // The shared marker only passes audience classification. Every management
    // method still requires OPS, then the service authorizes the provider type.

    @GetMapping
    @Operation(summary = "获取模型供应器列表")
    @SharedOfficialEndpoint
    public Result<PageData<ModelProviderDTO>> getListPage(ModelProviderDTO modelProviderDTO,
            @RequestParam(required = true, defaultValue = "0") String page,
            @RequestParam(required = true, defaultValue = "10") String limit) {
        OpsAccess.employee();
        return new Result<PageData<ModelProviderDTO>>()
                .ok(operations.page(modelProviderDTO, page, limit));
    }

    @PostMapping
    @Operation(summary = "新增模型供应器")
    @SharedOfficialEndpoint
    public Result<ModelProviderDTO> add(@RequestBody @Validated ModelProviderDTO modelProviderDTO) {
        OpsAccess.employee();
        ModelProviderDTO resp = operations.add(modelProviderDTO);
        return new Result<ModelProviderDTO>().ok(resp);
    }

    @PutMapping
    @Operation(summary = "修改模型供应器")
    @SharedOfficialEndpoint
    public Result<ModelProviderDTO> edit(@RequestBody @Validated(UpdateGroup.class) ModelProviderDTO modelProviderDTO) {
        OpsAccess.employee();
        ModelProviderDTO resp = operations.edit(modelProviderDTO);
        return new Result<ModelProviderDTO>().ok(resp);
    }

    @PostMapping("/delete")
    @Operation(summary = "删除模型供应器")
    @SharedOfficialEndpoint
    @Parameter(name = "ids", description = "ID数组", required = true)
    public Result<Void> delete(@RequestBody List<String> ids) {
        OpsAccess.employee();
        operations.delete(ids);
        return new Result<>();
    }

    @GetMapping("/plugin/names")
    @Tag(name = "获取插件名称列表")
    public Result<List<ModelProviderDTO>> getPluginNameList() {
        return ResultUtils.success(modelProviderService.getPluginList());
    }

}

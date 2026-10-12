package xiaozhi.modules.model.controller;

import java.util.List;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import xiaozhi.common.page.PageData;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.agent.service.AgentTemplateService;
import xiaozhi.modules.config.service.ConfigService;
import xiaozhi.modules.model.dto.ModelAssistantApplyRequestDTO;
import xiaozhi.modules.model.dto.ModelAssistantApplyResponseDTO;
import xiaozhi.modules.model.dto.LlmModelBasicInfoDTO;
import xiaozhi.modules.model.dto.ModelAssistantRequestDTO;
import xiaozhi.modules.model.dto.ModelAssistantResponseDTO;
import xiaozhi.modules.model.dto.ModelAssistantSettingsDTO;
import xiaozhi.modules.model.dto.ModelBenchmarkCaseGenerateRequestDTO;
import xiaozhi.modules.model.dto.ModelBenchmarkCaseGenerateResponseDTO;
import xiaozhi.modules.model.dto.ModelBenchmarkCreateResponseDTO;
import xiaozhi.modules.model.dto.ModelBenchmarkTaskDTO;
import xiaozhi.modules.model.dto.ModelBasicInfoDTO;
import xiaozhi.modules.model.dto.ModelCatalogDTO;
import xiaozhi.modules.model.dto.ModelConfigBodyDTO;
import xiaozhi.modules.model.dto.ModelConfigDTO;
import xiaozhi.modules.model.dto.ModelMigrationPreviewDTO;
import xiaozhi.modules.model.dto.ModelPerformanceTestRequestDTO;
import xiaozhi.modules.model.dto.ModelProviderDTO;
import xiaozhi.modules.model.dto.ModelTestResultDTO;
import xiaozhi.modules.model.dto.VoiceDTO;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.ModelBenchmarkService;
import xiaozhi.modules.model.service.ModelCatalogService;
import xiaozhi.modules.model.service.ModelAssistantService;
import xiaozhi.modules.model.service.ModelConfigService;
import xiaozhi.modules.model.service.ModelProviderService;
import xiaozhi.modules.timbre.service.TimbreService;

@AllArgsConstructor
@RestController
@RequestMapping("/models")
@Tag(name = "模型配置")
public class ModelController {

    private final ModelProviderService modelProviderService;
    private final TimbreService timbreService;
    private final ModelConfigService modelConfigService;
    private final ModelBenchmarkService modelBenchmarkService;
    private final ModelCatalogService modelCatalogService;
    private final ModelAssistantService modelAssistantService;
    private final ConfigService configService;
    private final AgentTemplateService agentTemplateService;

    @GetMapping("/names")
    @Operation(summary = "获取所有模型名称")
    @RequiresPermissions("sys:role:normal")
    public Result<List<ModelBasicInfoDTO>> getModelNames(@RequestParam String modelType,
            @RequestParam(required = false) String modelName) {
        List<ModelBasicInfoDTO> modelList = modelConfigService.getModelCodeList(modelType, modelName);
        return new Result<List<ModelBasicInfoDTO>>().ok(modelList);
    }

    @GetMapping("/llm/names")
    @Operation(summary = "获取LLM模型信息")
    @RequiresPermissions("sys:role:normal")
    public Result<List<LlmModelBasicInfoDTO>> getLlmModelCodeList(@RequestParam(required = false) String modelName) {
        List<LlmModelBasicInfoDTO> llmModelCodeList = modelConfigService.getLlmModelCodeList(modelName);
        return new Result<List<LlmModelBasicInfoDTO>>().ok(llmModelCodeList);
    }

    @GetMapping("/{modelType}/provideTypes")
    @Operation(summary = "获取模型供应器列表")
    @RequiresPermissions("model.catalog.read")
    public Result<List<ModelProviderDTO>> getModelProviderList(@PathVariable String modelType) {
        List<ModelProviderDTO> modelProviderDTOS = modelProviderService.getListByModelType(modelType);
        return new Result<List<ModelProviderDTO>>().ok(modelProviderDTOS);
    }

    @GetMapping("/list")
    @Operation(summary = "获取模型配置列表")
    @RequiresPermissions("model.catalog.read")
    public Result<PageData<ModelConfigDTO>> getModelConfigList(
            @RequestParam(required = true) String modelType,
            @RequestParam(required = false) String modelName,
            @RequestParam(required = true, defaultValue = "0") String page,
            @RequestParam(required = true, defaultValue = "10") String limit) {
        PageData<ModelConfigDTO> pageList = modelConfigService.getPageList(modelType, modelName, page, limit);
        return new Result<PageData<ModelConfigDTO>>().ok(pageList);
    }

    @GetMapping("/catalog")
    @Operation(summary = "获取标准模型配置目录")
    @RequiresPermissions("model.catalog.read")
    public Result<List<ModelCatalogDTO>> getModelCatalog(
            @RequestParam(required = false) String modelType,
            @RequestParam(required = false) String providerCode,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String capability,
            @RequestParam(required = false) String vendor,
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String keyword) {
        return new Result<List<ModelCatalogDTO>>()
                .ok(modelCatalogService.getCatalog(modelType,
                        org.apache.commons.lang3.StringUtils.defaultIfBlank(providerCode, provider),
                        capability,
                        org.apache.commons.lang3.StringUtils.defaultIfBlank(vendor, platform),
                        keyword));
    }

    @GetMapping("/templates")
    @Operation(summary = "获取标准模型配置模板")
    @RequiresPermissions("model.catalog.read")
    public Result<List<ModelCatalogDTO>> getModelTemplates(
            @RequestParam(required = false) String modelType,
            @RequestParam(required = false) String providerCode,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String catalogId) {
        return new Result<List<ModelCatalogDTO>>().ok(modelCatalogService.getTemplates(modelType,
                org.apache.commons.lang3.StringUtils.defaultIfBlank(providerCode, provider)));
    }

    @PostMapping("/migration/preview")
    @Operation(summary = "预览模型配置标准化结果")
    @RequiresPermissions("model.catalog.write")
    public Result<List<ModelMigrationPreviewDTO>> previewModelMigration(
            @RequestParam(required = false) String modelType) {
        return new Result<List<ModelMigrationPreviewDTO>>().ok(modelCatalogService.previewMigration(modelType));
    }

    @GetMapping("/assistant/settings")
    @Operation(summary = "获取模型配置助手设置")
    @RequiresPermissions("model.catalog.read")
    public Result<ModelAssistantSettingsDTO> getAssistantSettings() {
        return new Result<ModelAssistantSettingsDTO>().ok(modelAssistantService.getSettings());
    }

    @PutMapping("/assistant/settings")
    @Operation(summary = "更新模型配置助手设置")
    @RequiresPermissions("model.catalog.write")
    public Result<ModelAssistantSettingsDTO> updateAssistantSettings(
            @RequestBody ModelAssistantSettingsDTO settings) {
        return new Result<ModelAssistantSettingsDTO>().ok(modelAssistantService.updateSettings(settings));
    }

    @PostMapping("/assistant/chat")
    @Operation(summary = "模型配置智能助手")
    @RequiresPermissions("model.catalog.test")
    public Result<ModelAssistantResponseDTO> chatWithAssistant(
            @RequestBody ModelAssistantRequestDTO request) {
        return new Result<ModelAssistantResponseDTO>().ok(modelAssistantService.chat(request));
    }

    @PostMapping("/assistant/apply-artifacts")
    @Operation(summary = "模型配置助手写入接入产物")
    @RequiresPermissions("model.catalog.write")
    public Result<ModelAssistantApplyResponseDTO> applyAssistantArtifacts(
            @RequestBody ModelAssistantApplyRequestDTO request) {
        return new Result<ModelAssistantApplyResponseDTO>().ok(modelAssistantService.applyArtifacts(request));
    }

    @PostMapping("/{modelType}/{provideCode}")
    @Operation(summary = "新增模型配置")
    @RequiresPermissions("model.catalog.write")
    public Result<ModelConfigDTO> addModelConfig(@PathVariable String modelType,
            @PathVariable String provideCode,
            @RequestBody ModelConfigBodyDTO modelConfigBodyDTO) {
        ModelConfigDTO modelConfigDTO = modelConfigService.add(modelType, provideCode, modelConfigBodyDTO);
        configService.getConfig(false);
        return new Result<ModelConfigDTO>().ok(modelConfigDTO);
    }

    @PutMapping("/{modelType}/{provideCode}/{id}")
    @Operation(summary = "编辑模型配置")
    @RequiresPermissions("model.catalog.write")
    public Result<ModelConfigDTO> editModelConfig(@PathVariable String modelType,
            @PathVariable String provideCode,
            @PathVariable String id,
            @RequestBody ModelConfigBodyDTO modelConfigBodyDTO) {
        ModelConfigDTO modelConfigDTO = modelConfigService.edit(modelType, provideCode, id, modelConfigBodyDTO);
        configService.getConfig(false);
        return new Result<ModelConfigDTO>().ok(modelConfigDTO);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除模型配置")
    @RequiresPermissions("model.catalog.write")
    public Result<Void> deleteModelConfig(@PathVariable String id) {
        modelConfigService.delete(id);
        return new Result<>();
    }

    @PostMapping("/test/{id}")
    @Operation(summary = "测试模型配置")
    @RequiresPermissions("model.catalog.test")
    public Result<ModelTestResultDTO> testModelConfig(@PathVariable String id) {
        ModelTestResultDTO testResult = modelConfigService.testModel(id);
        if (Boolean.TRUE.equals(testResult.getSuccess())) {
            return new Result<ModelTestResultDTO>().ok(testResult);
        }
        Result<ModelTestResultDTO> result = new Result<ModelTestResultDTO>().error(testResult.getMessage());
        result.setData(testResult);
        return result;
    }

    @PostMapping("/performance-test")
    @Operation(summary = "创建模型专业评测任务")
    @RequiresPermissions("model.catalog.test")
    public Result<ModelBenchmarkCreateResponseDTO> performanceTest(
            @RequestBody ModelPerformanceTestRequestDTO request) {
        return new Result<ModelBenchmarkCreateResponseDTO>().ok(modelBenchmarkService.createBenchmarkTask(request));
    }

    @PostMapping("/benchmark/cases")
    @Operation(summary = "生成模型专业评测候选用例")
    @RequiresPermissions("model.catalog.test")
    public Result<ModelBenchmarkCaseGenerateResponseDTO> benchmarkCases(
            @RequestBody(required = false) ModelBenchmarkCaseGenerateRequestDTO request) {
        return new Result<ModelBenchmarkCaseGenerateResponseDTO>().ok(modelBenchmarkService.generateBenchmarkCases(request));
    }

    @GetMapping("/benchmark/{taskId}")
    @Operation(summary = "获取模型专业评测任务")
    @RequiresPermissions("model.catalog.test")
    public Result<ModelBenchmarkTaskDTO> benchmarkTask(@PathVariable String taskId) {
        return new Result<ModelBenchmarkTaskDTO>().ok(modelBenchmarkService.getBenchmarkTask(taskId));
    }

    @GetMapping("/benchmark")
    @Operation(summary = "获取模型专业评测历史任务")
    @RequiresPermissions("model.catalog.test")
    public Result<List<ModelBenchmarkTaskDTO>> benchmarkTaskHistory(
            @RequestParam(required = false) String modelType,
            @RequestParam(required = false, defaultValue = "20") Integer limit) {
        return new Result<List<ModelBenchmarkTaskDTO>>().ok(modelBenchmarkService.listBenchmarkTasks(modelType, limit));
    }

    @DeleteMapping("/benchmark/{taskId}")
    @Operation(summary = "删除模型专业评测历史任务")
    @RequiresPermissions("model.catalog.test")
    public Result<Void> deleteBenchmarkTask(@PathVariable String taskId) {
        modelBenchmarkService.deleteBenchmarkTask(taskId);
        return new Result<>();
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取模型配置")
    @RequiresPermissions("model.catalog.read")
    public Result<ModelConfigDTO> getModelConfig(@PathVariable String id) {
        return new Result<ModelConfigDTO>().ok(modelConfigService.getDTOById(id));
    }

    @PutMapping("/enable/{id}/{status}")
    @Operation(summary = "启用/设为非启用模型配置")
    @RequiresPermissions("model.catalog.write")
    public Result<Void> enableModelConfig(@PathVariable String id, @PathVariable Integer status) {
        ModelConfigEntity entity = modelConfigService.selectById(id);
        if (entity == null) {
            return new Result<Void>().error("模型配置不存在");
        }
        if (status == 1 && (entity.getTestStatus() == null || entity.getTestStatus() != 1)) {
            return new Result<Void>().error("模型未测试通过，不能启用");
        }
        // 不更新ConfigJson字段
        entity.setConfigJson(null);
        entity.setIsEnabled(status);
        modelConfigService.updateById(entity);
        return new Result<Void>();
    }

    @PutMapping("/default/{id}")
    @Operation(summary = "设置默认模型")
    @RequiresPermissions("model.catalog.write")
    public Result<Void> setDefaultModel(@PathVariable String id) {
        ModelConfigEntity entity = modelConfigService.selectById(id);
        if (entity == null) {
            return new Result<Void>().error("模型配置不存在");
        }
        if (entity.getTestStatus() == null || entity.getTestStatus() != 1) {
            return new Result<Void>().error("模型未测试通过，不能设置为默认模型");
        }
        // 将其他模型设置为非默认
        modelConfigService.setDefaultModel(entity.getModelType(), 0);
        entity.setIsEnabled(1);
        entity.setIsDefault(1);
        // 不更新ConfigJson字段
        entity.setConfigJson(null);
        modelConfigService.updateById(entity);

        // 更新模板表中对应的模型ID
        agentTemplateService.updateDefaultTemplateModelId(entity.getModelType(), entity.getId());

        configService.getConfig(false);
        return new Result<Void>();
    }

    @GetMapping("/{modelId}/voices")
    @Operation(summary = "获取模型音色")
    @RequiresPermissions("sys:role:normal")
    public Result<List<VoiceDTO>> getVoiceList(@PathVariable String modelId,
            @RequestParam(required = false) String voiceName) {
        List<VoiceDTO> voiceList = timbreService.getVoiceNames(modelId, voiceName);
        return new Result<List<VoiceDTO>>().ok(voiceList);
    }
}

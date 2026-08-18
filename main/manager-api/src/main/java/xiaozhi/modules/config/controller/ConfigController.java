package xiaozhi.modules.config.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import xiaozhi.common.utils.Result;
import xiaozhi.common.validator.ValidatorUtils;
import xiaozhi.modules.agent.service.AgentMcpNoCodeToolService;
import xiaozhi.modules.config.dto.AgentModelsDTO;
import xiaozhi.modules.config.dto.CorrectWordsDTO;
import xiaozhi.modules.config.dto.DeviceSpeechInputRouteDTO;
import xiaozhi.modules.config.service.ConfigService;
import xiaozhi.modules.device.service.DeviceService;
import xiaozhi.modules.knowledge.dto.document.RetrievalDTO;
import xiaozhi.modules.knowledge.service.KnowledgeFilesService;

/**
 * xiaozhi-server 配置获取
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("config")
@Tag(name = "参数管理")
@AllArgsConstructor
public class ConfigController {
    private final ConfigService configService;
    private final KnowledgeFilesService knowledgeFilesService;
    private final AgentMcpNoCodeToolService agentMcpNoCodeToolService;
    private final DeviceService deviceService;

    @PostMapping("server-base")
    @Operation(summary = "服务端获取配置接口")
    public Result<Object> getConfig() {
        Object config = configService.getConfig(true);
        return new Result<Object>().ok(config);
    }

    @PostMapping("agent-models")
    @Operation(summary = "获取智能体模型")
    public Result<Object> getAgentModels(@Valid @RequestBody AgentModelsDTO dto) {
        // 效验数据
        ValidatorUtils.validateEntity(dto);
        Object models = configService.getAgentModels(dto.getMacAddress(), dto.getSelectedModule());
        return new Result<Object>().ok(models);
    }

    @PostMapping("correct-words")
    @Operation(summary = "获取智能体替换词")
    public Result<Object> getCorrectWords(@Valid @RequestBody CorrectWordsDTO dto) {
        ValidatorUtils.validateEntity(dto);
        List<String> list = configService.getCorrectWords(dto.getMacAddress());
        return new Result<Object>().ok(list);
    }

    @PostMapping("device-speech-input-route")
    @Operation(summary = "服务端更新当前认证设备的语音输入路由")
    public Result<Object> updateDeviceSpeechInputRoute(
            @Valid @RequestBody DeviceSpeechInputRouteDTO dto) {
        deviceService.updateSpeechInputModeFromRuntime(
                dto.getMacAddress(), dto.getInputPolicy());
        return new Result<Object>().ok(null);
    }

    @PostMapping("knowledge/retrieval")
    @Operation(summary = "服务端知识库召回接口")
    public Result<Object> retrieval(@RequestBody Map<String, Object> body) {
        // Safely extract dataset_ids and question with type checks
        Object datasetIdsObj = body.get("dataset_ids");
        @SuppressWarnings("unchecked")
        List<String> datasetIds = new ArrayList<>();
        if (datasetIdsObj instanceof List<?>) {
            for (Object item : (List<?>) datasetIdsObj) {
                if (item instanceof String) {
                    datasetIds.add((String) item);
                }
            }
        }
        String question = null;
        Object questionObj = body.get("question");
        if (questionObj instanceof String) {
            question = (String) questionObj;
        }
        RetrievalDTO.TestReq req = RetrievalDTO.TestReq.builder()
                .datasetIds(datasetIds)
                .question(question)
                .page(1)
                .pageSize(10)
                .build();
        return new Result<Object>().ok(knowledgeFilesService.retrievalTest(req));
    }

    @GetMapping("mcp/no-code-tools/{agentId}")
    @Operation(summary = "服务端获取可视化MCP HTTP工具配置")
    public Result<Object> getNoCodeMcpTools(@PathVariable String agentId) {
        return new Result<Object>().ok(agentMcpNoCodeToolService.getToolsByAgentId(agentId));
    }
}

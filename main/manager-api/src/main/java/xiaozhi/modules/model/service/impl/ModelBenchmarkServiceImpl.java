package xiaozhi.modules.model.service.impl;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang3.StringUtils;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.config.service.ConfigService;
import xiaozhi.modules.model.dao.ModelBenchmarkResultDao;
import xiaozhi.modules.model.dao.ModelBenchmarkSampleDao;
import xiaozhi.modules.model.dao.ModelBenchmarkTaskDao;
import xiaozhi.modules.model.dao.ModelConfigDao;
import xiaozhi.modules.model.dto.ModelBenchmarkCreateResponseDTO;
import xiaozhi.modules.model.dto.ModelBenchmarkTaskDTO;
import xiaozhi.modules.model.dto.ModelPerformanceTestRequestDTO;
import xiaozhi.modules.model.dto.ModelPerformanceTestResultDTO;
import xiaozhi.modules.model.dto.ModelPerformanceTestSampleDTO;
import xiaozhi.modules.model.entity.ModelBenchmarkResultEntity;
import xiaozhi.modules.model.entity.ModelBenchmarkSampleEntity;
import xiaozhi.modules.model.entity.ModelBenchmarkTaskEntity;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.ModelBenchmarkService;
import xiaozhi.modules.sys.service.SysParamsService;

@Service
@AllArgsConstructor
@Slf4j
public class ModelBenchmarkServiceImpl implements ModelBenchmarkService {

    private static final int DEFAULT_ITERATIONS = 3;
    private static final int MAX_ITERATIONS = 10;
    private static final int DEFAULT_CONCURRENCY = 3;
    private static final int MAX_CONCURRENCY = 8;
    private static final int MAX_MODELS = 50;
    private static final Executor BENCHMARK_EXECUTOR = Executors.newFixedThreadPool(2);
    private static final Map<String, JSONObject> JUDGE_CONFIGS = new ConcurrentHashMap<>();
    private static final Map<String, JSONObject> AGENT_CONTEXTS = new ConcurrentHashMap<>();
    private static final List<String> METRIC_NOTES = List.of(
            "专业评测由 xiaozhi-server 真实 provider 执行，不再用 Java 配置校验冒充模型请求。",
            "LLM/SLM 使用业务样本和 LLMJudge 六维评分，覆盖酒水推荐、情绪陪伴、安全边界、RAG事实、多轮记忆和闲聊。",
            "LLMJudge 默认使用 DeepSeek/OpenAI Compatible 非推理评审模型；不可用时自动降级为启发式评估，并在报告中标记评审模式。",
            "ASR 关注真实音频识别延时、空识别率、CER/WER；缺少参考文本时不计算准确率。",
            "TTS 关注合成总耗时、首音频耗时、音频有效性和 RTF。",
            "未覆盖的指标显示为未实测，不计入失败，也不修改模型启用状态。");

    private final ModelBenchmarkTaskDao taskDao;
    private final ModelBenchmarkResultDao resultDao;
    private final ModelBenchmarkSampleDao sampleDao;
    private final ModelConfigDao modelConfigDao;
    private final AgentDao agentDao;
    private final ConfigService configService;
    private final SysParamsService sysParamsService;
    private final Environment environment;

    @Override
    public ModelBenchmarkCreateResponseDTO createBenchmarkTask(ModelPerformanceTestRequestDTO request) {
        if (request == null || CollectionUtil.isEmpty(request.getModelIds())) {
            throw new RenException(ErrorCode.PARAMS_GET_ERROR);
        }
        List<String> modelIds = request.getModelIds().stream()
                .filter(StringUtils::isNotBlank)
                .distinct()
                .limit(MAX_MODELS)
                .toList();
        if (modelIds.isEmpty()) {
            throw new RenException(ErrorCode.IDENTIFIER_NOT_NULL);
        }

        ModelBenchmarkTaskEntity task = new ModelBenchmarkTaskEntity();
        task.setId(newId());
        task.setModelType(request.getModelType());
        task.setModelIds(JSONUtil.toJsonStr(modelIds));
        task.setIterations(normalize(request.getIterations(), DEFAULT_ITERATIONS, MAX_ITERATIONS));
        task.setConcurrency(normalize(request.getConcurrency(), DEFAULT_CONCURRENCY, MAX_CONCURRENCY));
        task.setStatus("PENDING");
        task.setProgress(0);
        task.setMetricNotes(JSONUtil.toJsonStr(METRIC_NOTES));
        Date now = new Date();
        task.setCreateDate(now);
        task.setUpdateDate(now);
        taskDao.insert(task);

        JSONObject judgeConfig = buildJudgeConfig(request);
        if (!judgeConfig.isEmpty()) {
            JUDGE_CONFIGS.put(task.getId(), judgeConfig);
        }
        JSONObject agentContext = buildAgentContext(request);
        if (!agentContext.isEmpty()) {
            AGENT_CONTEXTS.put(task.getId(), agentContext);
        }
        CompletableFuture.runAsync(() -> runTask(task.getId()), BENCHMARK_EXECUTOR);

        ModelBenchmarkCreateResponseDTO response = new ModelBenchmarkCreateResponseDTO();
        response.setTaskId(task.getId());
        response.setStatus(task.getStatus());
        return response;
    }

    @Override
    public ModelBenchmarkTaskDTO getBenchmarkTask(String taskId) {
        if (StringUtils.isBlank(taskId)) {
            throw new RenException(ErrorCode.IDENTIFIER_NOT_NULL);
        }
        ModelBenchmarkTaskEntity task = taskDao.selectById(taskId);
        if (task == null) {
            throw new RenException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return toTaskDTO(task, true);
    }

    @Override
    public List<ModelBenchmarkTaskDTO> listBenchmarkTasks(String modelType, Integer limit) {
        int normalizedLimit = normalize(limit, 20, 100);
        QueryWrapper<ModelBenchmarkTaskEntity> query = new QueryWrapper<ModelBenchmarkTaskEntity>()
                .orderByDesc("create_date")
                .last("LIMIT " + normalizedLimit);
        if (StringUtils.isNotBlank(modelType)) {
            query.eq("model_type", modelType);
        }
        return taskDao.selectList(query).stream()
                .map(task -> toTaskDTO(task, false))
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteBenchmarkTask(String taskId) {
        if (StringUtils.isBlank(taskId)) {
            throw new RenException(ErrorCode.IDENTIFIER_NOT_NULL);
        }
        ModelBenchmarkTaskEntity task = taskDao.selectById(taskId);
        if (task == null) {
            throw new RenException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        String status = StringUtils.defaultString(task.getStatus());
        if (!List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(status)) {
            throw new RenException("评测任务仍在执行或等待中，请结束后再删除历史记录。");
        }
        sampleDao.delete(new QueryWrapper<ModelBenchmarkSampleEntity>().eq("task_id", taskId));
        resultDao.delete(new QueryWrapper<ModelBenchmarkResultEntity>().eq("task_id", taskId));
        taskDao.deleteById(taskId);
        JUDGE_CONFIGS.remove(taskId);
        AGENT_CONTEXTS.remove(taskId);
    }

    private void runTask(String taskId) {
        ModelBenchmarkTaskEntity task = taskDao.selectById(taskId);
        if (task == null) {
            return;
        }
        long startedMs = System.currentTimeMillis();
        updateTask(taskId, "RUNNING", 5, "专业评测任务已开始", null, new Date(), null, null);
        try {
            List<JSONObject> models = buildPythonBenchmarkModels(task);
            runModelBenchmarks(task, models);
            long durationMs = System.currentTimeMillis() - startedMs;
            updateTask(taskId, "SUCCEEDED", 100, buildTaskSummary(taskId), null, null, new Date(), durationMs);
        } catch (Exception e) {
            log.warn("模型专业评测任务失败，taskId: {}", taskId, e);
            updateTask(taskId, "FAILED", 100, "专业评测任务失败", e.getMessage(), null, new Date(),
                    System.currentTimeMillis() - startedMs);
        } finally {
            JUDGE_CONFIGS.remove(taskId);
            AGENT_CONTEXTS.remove(taskId);
        }
    }

    private JSONObject buildJudgeConfig(ModelPerformanceTestRequestDTO request) {
        JSONObject config = new JSONObject();
        if (StringUtils.isBlank(request.getJudgeModelId())) {
            return config;
        }
        ModelConfigEntity judgeModel = modelConfigDao.selectById(request.getJudgeModelId().trim());
        if (judgeModel == null || judgeModel.getConfigJson() == null) {
            throw new RenException("评审模型配置不存在或配置为空，请在模型配置中选择一个可用的 LLM。");
        }
        if (!"LLM".equalsIgnoreCase(StringUtils.defaultString(judgeModel.getModelType()))
                && !"SLM".equalsIgnoreCase(StringUtils.defaultString(judgeModel.getModelType()))) {
            throw new RenException("评审模型必须选择已有的 LLM/SLM 模型配置。");
        }
        config.set("model_id", judgeModel.getId());
        config.set("model_name", judgeModel.getModelName());
        config.set("model_type", judgeModel.getModelType());
        config.set("provider_type", judgeModel.getConfigJson().getStr("type"));
        config.set("config", judgeModel.getConfigJson());
        return config;
    }

    private JSONObject buildAgentContext(ModelPerformanceTestRequestDTO request) {
        JSONObject context = new JSONObject();
        if (StringUtils.isBlank(request.getAgentId())) {
            return context;
        }
        String agentId = request.getAgentId().trim();
        AgentEntity agent = agentDao.selectById(agentId);
        if (agent == null) {
            throw new RenException("测试智能体不存在，请重新选择。");
        }
        Map<String, Object> runtimeConfig = configService.getAgentRuntimeConfig(agentId);
        context.set("agent_id", agent.getId());
        context.set("agent_name", agent.getAgentName());
        context.set("runtime_config", runtimeConfig == null ? Map.of() : runtimeConfig);
        return context;
    }

    private void runModelBenchmarks(ModelBenchmarkTaskEntity task, List<JSONObject> models) {
        int total = models.size();
        int concurrency = Math.max(1, Math.min(task.getConcurrency() == null ? DEFAULT_CONCURRENCY : task.getConcurrency(),
                Math.min(MAX_CONCURRENCY, total)));
        AtomicInteger completed = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);
        ExecutorService modelExecutor = Executors.newFixedThreadPool(concurrency);
        try {
            List<CompletableFuture<Void>> futures = models.stream()
                    .map(model -> CompletableFuture.runAsync(() -> {
                        try {
                            JSONObject payload = buildPythonBenchmarkPayload(task, List.of(model));
                            JSONObject response = callPythonBenchmark(payload);
                            persistPythonBenchmarkResult(task.getId(), response);
                        } catch (Exception e) {
                            failed.incrementAndGet();
                            log.warn("模型专业评测单模型失败，taskId: {}, modelId: {}", task.getId(), model.getStr("id"), e);
                            persistFailedModelResult(task.getId(), model, e.getMessage());
                        } finally {
                            int done = completed.incrementAndGet();
                            int progress = 5 + (int) Math.round(done * 90.0 / total);
                            String summary = String.format("专业评测执行中，已完成 %d/%d 个模型，失败 %d 个", done, total, failed.get());
                            updateTask(task.getId(), "RUNNING", Math.min(progress, 95), summary, null, null, null, null);
                        }
                    }, modelExecutor))
                    .toList();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } finally {
            modelExecutor.shutdown();
        }
    }

    private List<JSONObject> buildPythonBenchmarkModels(ModelBenchmarkTaskEntity task) {
        List<String> modelIds = parseStringList(task.getModelIds());
        List<JSONObject> models = new ArrayList<>();
        List<String> skippedModels = new ArrayList<>();
        for (String modelId : modelIds) {
            ModelConfigEntity model = modelConfigDao.selectById(modelId);
            if (model == null || model.getConfigJson() == null) {
                skippedModels.add(modelId);
                continue;
            }
            JSONObject item = new JSONObject();
            item.set("id", model.getId());
            item.set("model_name", model.getModelName());
            item.set("model_type", model.getModelType());
            item.set("provider_type", model.getConfigJson().getStr("type"));
            item.set("config", model.getConfigJson());
            models.add(item);
        }
        if (models.isEmpty()) {
            throw new RenException("没有可评测的模型配置。请确认已选择的模型仍存在，并且模型配置 JSON 不为空。已跳过模型ID："
                    + String.join(",", skippedModels));
        }
        return models;
    }

    private JSONObject buildPythonBenchmarkPayload(ModelBenchmarkTaskEntity task, List<JSONObject> models) {
        JSONArray modelArray = new JSONArray();
        for (JSONObject model : models) {
            modelArray.add(model);
        }
        JSONObject payload = new JSONObject();
        payload.set("task_id", task.getId());
        payload.set("model_type", task.getModelType());
        payload.set("iterations", task.getIterations());
        payload.set("concurrency", task.getConcurrency());
        payload.set("models", modelArray);
        JSONObject judgeConfig = JUDGE_CONFIGS.get(task.getId());
        if (judgeConfig != null && !judgeConfig.isEmpty()) {
            payload.set("judge_config", judgeConfig);
        }
        JSONObject agentContext = AGENT_CONTEXTS.get(task.getId());
        if (agentContext != null && !agentContext.isEmpty()
                && ("LLM".equalsIgnoreCase(task.getModelType()) || "SLM".equalsIgnoreCase(task.getModelType()))) {
            payload.set("agent_context", agentContext);
        }
        return payload;
    }

    private JSONObject callPythonBenchmark(JSONObject payload) {
        String baseUrl = resolveXiaozhiServerHttpBaseUrl();
        String url = baseUrl + "/benchmark/models";
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofMinutes(10));
        RestTemplate benchmarkRestTemplate = new RestTemplate(factory);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response;
        try {
            response = benchmarkRestTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(payload.toString(), headers), String.class);
        } catch (ResourceAccessException e) {
            throw new RenException("无法连接 xiaozhi-server 专业评测接口：" + url
                    + "。请先确认 xiaozhi-server 已启动且 HTTP 端口可访问：执行 `bash scripts/dev status`，"
                    + "确认 server 的 8003 端口为 listening；如果 manager-api 与 xiaozhi-server 不在同一主机或容器，"
                    + "请配置 `xiaozhi.server.http-base-url`，例如 http://xiaozhi-esp32-server:8003。原始错误："
                    + e.getMessage(), e);
        }
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RenException("xiaozhi-server 专业评测接口返回异常：" + response.getStatusCode());
        }
        return JSONUtil.parseObj(response.getBody());
    }

    private String resolveXiaozhiServerHttpBaseUrl() {
        String configured = environment.getProperty("xiaozhi.server.http-base-url");
        if (StringUtils.isNotBlank(configured)) {
            return StringUtils.removeEnd(configured.trim(), "/");
        }
        return "http://127.0.0.1:" + resolveXiaozhiServerHttpPort();
    }

    private int resolveXiaozhiServerHttpPort() {
        String value = sysParamsService.getValue("server.http_port", true);
        if (StringUtils.isNumeric(value)) {
            return Integer.parseInt(value);
        }
        return 8003;
    }

    private void persistPythonBenchmarkResult(String taskId, JSONObject response) {
        JSONArray results = response.getJSONArray("results");
        if (results == null) {
            return;
        }
        for (Object resultObj : results) {
            JSONObject resultJson = JSONUtil.parseObj(resultObj);
            String resultId = newId();
            ModelBenchmarkResultEntity result = new ModelBenchmarkResultEntity();
            result.setId(resultId);
            result.setTaskId(taskId);
            result.setModelId(resultJson.getStr("model_id"));
            result.setModelName(resultJson.getStr("model_name"));
            result.setModelType(resultJson.getStr("model_type"));
            result.setProviderType(resultJson.getStr("provider_type"));
            result.setBenchmarkLevel(resultJson.getStr("benchmark_level"));
            result.setBenchmarkLevelText(resultJson.getStr("benchmark_level_text"));
            result.setSuccessCount(resultJson.getInt("success_count", 0));
            result.setFailureCount(resultJson.getInt("failure_count", 0));
            result.setSuccessRate(resultJson.getDouble("success_rate", 0.0));
            result.setAvgLatencyMs(resultJson.getDouble("avg_latency_ms"));
            result.setP50LatencyMs(resultJson.getLong("p50_latency_ms"));
            result.setP90LatencyMs(resultJson.getLong("p90_latency_ms"));
            result.setP95LatencyMs(resultJson.getLong("p95_latency_ms"));
            result.setTokensPerSecond(resultJson.getDouble("tokens_per_second"));
            result.setTtftMs(resultJson.getLong("ttft_ms"));
            result.setFirstAudioMs(resultJson.getLong("first_audio_ms"));
            result.setRtf(resultJson.getDouble("rtf"));
            result.setCer(resultJson.getDouble("cer"));
            result.setWer(resultJson.getDouble("wer"));
            result.setQualityScore(resultJson.getDouble("quality_score"));
            result.setLogicScore(resultJson.getDouble("logic_score"));
            result.setContextScore(resultJson.getDouble("context_score"));
            result.setSummary(resultJson.getStr("summary"));
            result.setErrorSummary(resultJson.getStr("error_summary"));
            result.setCreateDate(new Date());
            resultDao.insert(result);

            JSONArray samples = resultJson.getJSONArray("samples");
            if (samples != null) {
                for (Object sampleObj : samples) {
                    persistSample(taskId, resultId, result.getModelId(), JSONUtil.parseObj(sampleObj));
                }
            }
        }
    }

    private void persistFailedModelResult(String taskId, JSONObject model, String message) {
        ModelBenchmarkResultEntity result = new ModelBenchmarkResultEntity();
        result.setId(newId());
        result.setTaskId(taskId);
        result.setModelId(model.getStr("id"));
        result.setModelName(model.getStr("model_name"));
        result.setModelType(model.getStr("model_type"));
        result.setProviderType(model.getStr("provider_type"));
        result.setBenchmarkLevel("FAILED");
        result.setBenchmarkLevelText("评测失败");
        result.setSuccessCount(0);
        result.setFailureCount(1);
        result.setSuccessRate(0.0);
        result.setSummary("模型评测失败");
        result.setErrorSummary(StringUtils.abbreviate(message, 1000));
        result.setCreateDate(new Date());
        resultDao.insert(result);
    }

    private void persistSample(String taskId, String resultId, String modelId, JSONObject sampleJson) {
        ModelBenchmarkSampleEntity sample = new ModelBenchmarkSampleEntity();
        sample.setId(newId());
        sample.setTaskId(taskId);
        sample.setResultId(resultId);
        sample.setModelId(modelId);
        sample.setIteration(sampleJson.getInt("iteration"));
        sample.setSampleType(sampleJson.getStr("sample_type"));
        sample.setSuccess(Boolean.TRUE.equals(sampleJson.getBool("success")) ? 1 : 0);
        sample.setMeasured(Boolean.TRUE.equals(sampleJson.getBool("measured")) ? 1 : 0);
        sample.setLatencyMs(sampleJson.getLong("latency_ms"));
        sample.setTtftMs(sampleJson.getLong("ttft_ms"));
        sample.setFirstAudioMs(sampleJson.getLong("first_audio_ms"));
        sample.setTokensPerSecond(sampleJson.getDouble("tokens_per_second"));
        sample.setRtf(sampleJson.getDouble("rtf"));
        sample.setCer(sampleJson.getDouble("cer"));
        sample.setWer(sampleJson.getDouble("wer"));
        sample.setQualityScore(sampleJson.getDouble("quality_score"));
        sample.setInputSummary(sampleJson.getStr("input_summary"));
        sample.setOutputSummary(sampleJson.getStr("output_summary"));
        sample.setReferenceOutput(sampleJson.getStr("reference_output"));
        sample.setMessage(sampleJson.getStr("message"));
        sample.setErrorType(sampleJson.getStr("error_type"));
        sample.setRawMetrics(sampleJson.toString());
        sample.setCreateDate(new Date());
        sampleDao.insert(sample);
    }

    private ModelBenchmarkTaskDTO toTaskDTO(ModelBenchmarkTaskEntity task, boolean includeResults) {
        ModelBenchmarkTaskDTO dto = new ModelBenchmarkTaskDTO();
        dto.setId(task.getId());
        dto.setModelType(task.getModelType());
        dto.setModelIds(parseStringList(task.getModelIds()));
        dto.setIterations(task.getIterations());
        dto.setConcurrency(task.getConcurrency());
        dto.setStatus(task.getStatus());
        dto.setProgress(task.getProgress());
        dto.setSummary(task.getSummary());
        dto.setErrorMessage(task.getErrorMessage());
        dto.setStartedAt(task.getStartedAt());
        dto.setFinishedAt(task.getFinishedAt());
        dto.setDurationMs(task.getDurationMs());
        dto.setCreateDate(task.getCreateDate());
        dto.setUpdateDate(task.getUpdateDate());
        dto.setMetricNotes(parseStringList(task.getMetricNotes()));
        if (includeResults) {
            dto.setResults(loadResultDTOs(task.getId()));
        }
        return dto;
    }

    private List<ModelPerformanceTestResultDTO> loadResultDTOs(String taskId) {
        List<ModelBenchmarkResultEntity> entities = resultDao.selectList(new QueryWrapper<ModelBenchmarkResultEntity>()
                .eq("task_id", taskId)
                .orderByAsc("create_date"));
        List<ModelPerformanceTestResultDTO> results = new ArrayList<>();
        for (ModelBenchmarkResultEntity entity : entities) {
            ModelPerformanceTestResultDTO dto = new ModelPerformanceTestResultDTO();
            dto.setModelId(entity.getModelId());
            dto.setModelName(entity.getModelName());
            dto.setModelType(entity.getModelType());
            dto.setProviderType(entity.getProviderType());
            dto.setTestLevel(entity.getBenchmarkLevel());
            dto.setTestLevelText(entity.getBenchmarkLevelText());
            dto.setMeasured(entity.getAvgLatencyMs() != null);
            dto.setSuccessCount(entity.getSuccessCount());
            dto.setFailureCount(entity.getFailureCount());
            dto.setSuccessRate(entity.getSuccessRate());
            dto.setAvgLatencyMs(entity.getAvgLatencyMs());
            dto.setP50LatencyMs(entity.getP50LatencyMs());
            dto.setP90LatencyMs(entity.getP90LatencyMs());
            dto.setP95LatencyMs(entity.getP95LatencyMs());
            dto.setTtftMs(entity.getTtftMs());
            dto.setTokensPerSecond(entity.getTokensPerSecond());
            dto.setFirstAudioMs(entity.getFirstAudioMs());
            dto.setRtf(entity.getRtf());
            dto.setCer(entity.getCer());
            dto.setWer(entity.getWer());
            dto.setQualityScore(entity.getQualityScore());
            dto.setLogicScore(entity.getLogicScore());
            dto.setContextScore(entity.getContextScore());
            dto.setSummary(entity.getSummary());
            dto.setSamples(loadSampleDTOs(taskId, entity.getId()));
            enrichJudgeSummary(dto);
            results.add(dto);
        }
        return results;
    }

    private List<ModelPerformanceTestSampleDTO> loadSampleDTOs(String taskId, String resultId) {
        List<ModelBenchmarkSampleEntity> samples = sampleDao.selectList(new QueryWrapper<ModelBenchmarkSampleEntity>()
                .eq("task_id", taskId)
                .eq("result_id", resultId)
                .orderByAsc("iteration"));
        return samples.stream().map(sample -> {
            JSONObject rawMetrics = parseRawMetrics(sample.getRawMetrics());
            ModelPerformanceTestSampleDTO dto = new ModelPerformanceTestSampleDTO();
            dto.setIteration(sample.getIteration());
            dto.setSuccess(sample.getSuccess() != null && sample.getSuccess() == 1);
            dto.setMeasured(sample.getMeasured() != null && sample.getMeasured() == 1);
            dto.setResponseTimeMs(sample.getLatencyMs());
            dto.setTestLevel(sample.getSampleType());
            dto.setSampleType(sample.getSampleType());
            dto.setMessage(sample.getMessage());
            dto.setInputSummary(sample.getInputSummary());
            dto.setOutputSummary(sample.getOutputSummary());
            dto.setReferenceOutput(sample.getReferenceOutput());
            dto.setTtftMs(sample.getTtftMs());
            dto.setFirstAudioMs(sample.getFirstAudioMs());
            dto.setTokensPerSecond(sample.getTokensPerSecond());
            dto.setRtf(sample.getRtf());
            dto.setCer(sample.getCer());
            dto.setWer(sample.getWer());
            dto.setQualityScore(sample.getQualityScore());
            dto.setErrorType(sample.getErrorType());
            dto.setProcessSteps(rawMetrics.getBeanList("process_steps", String.class));
            dto.setInputDetail(rawMetrics.getJSONObject("input_detail"));
            dto.setOutputDetail(rawMetrics.getJSONObject("output_detail"));
            JSONObject outputDetail = rawMetrics.getJSONObject("output_detail");
            if (outputDetail != null) {
                dto.setJudgeMode(outputDetail.getStr("judge_mode"));
                dto.setP0p1(outputDetail.getBeanList("p0p1", String.class));
                dto.setScenario(outputDetail.getStr("scenario"));
                dto.setRiskLevel(outputDetail.getStr("risk_level"));
            }
            return dto;
        }).toList();
    }

    private void enrichJudgeSummary(ModelPerformanceTestResultDTO dto) {
        if (dto.getSamples() == null || dto.getSamples().isEmpty()) {
            return;
        }
        JSONObject dimensionTotals = new JSONObject();
        JSONObject dimensionCounts = new JSONObject();
        JSONObject scenarioTotals = new JSONObject();
        JSONObject scenarioCounts = new JSONObject();
        List<String> judgeModes = new ArrayList<>();
        int p0p1FailureCount = 0;
        for (ModelPerformanceTestSampleDTO sample : dto.getSamples()) {
            Map<String, Object> outputDetail = sample.getOutputDetail();
            if (outputDetail == null) {
                continue;
            }
            Object judgeMode = outputDetail.get("judge_mode");
            if (judgeMode != null && !judgeModes.contains(String.valueOf(judgeMode))) {
                judgeModes.add(String.valueOf(judgeMode));
            }
            Object p0p1 = outputDetail.get("p0p1");
            if (p0p1 instanceof List<?> list && !list.isEmpty()) {
                p0p1FailureCount += 1;
            }
            Object scores = outputDetail.get("scores");
            if (scores instanceof Map<?, ?> scoreMap) {
                for (Map.Entry<?, ?> entry : scoreMap.entrySet()) {
                    String key = String.valueOf(entry.getKey());
                    Double value = toDouble(entry.getValue());
                    if (value == null) {
                        continue;
                    }
                    dimensionTotals.set(key, dimensionTotals.getDouble(key, 0.0) + value);
                    dimensionCounts.set(key, dimensionCounts.getInt(key, 0) + 1);
                }
            }
            Object scenario = outputDetail.get("scenario");
            if (scenario != null && sample.getQualityScore() != null) {
                String key = String.valueOf(scenario);
                scenarioTotals.set(key, scenarioTotals.getDouble(key, 0.0) + sample.getQualityScore());
                scenarioCounts.set(key, scenarioCounts.getInt(key, 0) + 1);
            }
        }
        if (!judgeModes.isEmpty()) {
            dto.setJudgeMode(String.join(",", judgeModes));
        }
        dto.setP0p1FailureCount(p0p1FailureCount);
        dto.setDimensionScores(averageMap(dimensionTotals, dimensionCounts));
        dto.setScenarioScores(averageMap(scenarioTotals, scenarioCounts));
        dto.setRecommendation(p0p1FailureCount > 0 ? "不推荐作为主对话模型" : "可进入候选对比");
    }

    private Map<String, Object> averageMap(JSONObject totals, JSONObject counts) {
        JSONObject result = new JSONObject();
        for (String key : totals.keySet()) {
            int count = counts.getInt(key, 0);
            if (count > 0) {
                result.set(key, Math.round((totals.getDouble(key, 0.0) / count) * 100.0) / 100.0);
            }
        }
        return result;
    }

    private Double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private JSONObject parseRawMetrics(String rawMetrics) {
        if (StringUtils.isBlank(rawMetrics) || !JSONUtil.isTypeJSON(rawMetrics)) {
            return new JSONObject();
        }
        JSONObject parsed = JSONUtil.parseObj(rawMetrics);
        sanitizeJsonNulls(parsed);
        return parsed;
    }

    private void sanitizeJsonNulls(JSONObject jsonObject) {
        if (jsonObject == null) {
            return;
        }
        for (String key : new ArrayList<>(jsonObject.keySet())) {
            Object value = jsonObject.get(key);
            if (value == null || "cn.hutool.json.JSONNull".equals(value.getClass().getName())) {
                jsonObject.remove(key);
            } else if (value instanceof JSONObject nestedObject) {
                sanitizeJsonNulls(nestedObject);
            } else if (value instanceof JSONArray array) {
                for (Object item : array) {
                    if (item instanceof JSONObject nestedItem) {
                        sanitizeJsonNulls(nestedItem);
                    }
                }
            }
        }
    }

    private void updateTask(String taskId, String status, Integer progress, String summary, String errorMessage,
            Date startedAt, Date finishedAt, Long durationMs) {
        ModelBenchmarkTaskEntity update = new ModelBenchmarkTaskEntity();
        update.setId(taskId);
        update.setStatus(status);
        update.setProgress(progress);
        update.setSummary(summary);
        update.setErrorMessage(errorMessage);
        update.setStartedAt(startedAt);
        update.setFinishedAt(finishedAt);
        update.setDurationMs(durationMs);
        update.setUpdateDate(new Date());
        taskDao.updateById(update);
    }

    private String buildTaskSummary(String taskId) {
        Long count = resultDao.selectCount(new QueryWrapper<ModelBenchmarkResultEntity>().eq("task_id", taskId));
        return "专业评测完成，共 " + count + " 个模型";
    }

    private int normalize(Integer value, int defaultValue, int maxValue) {
        if (value == null || value < 1) {
            return defaultValue;
        }
        return Math.min(value, maxValue);
    }

    private List<String> parseStringList(String json) {
        if (StringUtils.isBlank(json)) {
            return List.of();
        }
        try {
            JSONArray array = JSONUtil.parseArray(json);
            return array.stream().map(String::valueOf).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}

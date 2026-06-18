package xiaozhi.modules.model.service.impl;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
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
    private static final List<String> METRIC_NOTES = List.of(
            "专业评测由 xiaozhi-server 真实 provider 执行，不再用 Java 配置校验冒充模型请求。",
            "LLM 关注延时、首 token、tokens/s、空回复率、逻辑性和上下文稳定性。",
            "ASR 关注真实音频识别延时、空识别率、CER/WER；缺少参考文本时不计算准确率。",
            "TTS 关注合成总耗时、首音频耗时、音频有效性和 RTF。",
            "未覆盖的指标显示为未实测，不计入失败，也不修改模型启用状态。");

    private final ModelBenchmarkTaskDao taskDao;
    private final ModelBenchmarkResultDao resultDao;
    private final ModelBenchmarkSampleDao sampleDao;
    private final ModelConfigDao modelConfigDao;
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
        return toTaskDTO(task);
    }

    private void runTask(String taskId) {
        ModelBenchmarkTaskEntity task = taskDao.selectById(taskId);
        if (task == null) {
            return;
        }
        long startedMs = System.currentTimeMillis();
        updateTask(taskId, "RUNNING", 5, "专业评测任务已开始", null, new Date(), null, null);
        try {
            JSONObject payload = buildPythonBenchmarkPayload(task);
            JSONObject response = callPythonBenchmark(payload);
            persistPythonBenchmarkResult(taskId, response);
            long durationMs = System.currentTimeMillis() - startedMs;
            updateTask(taskId, "SUCCEEDED", 100, buildTaskSummary(taskId), null, null, new Date(), durationMs);
        } catch (Exception e) {
            log.warn("模型专业评测任务失败，taskId: {}", taskId, e);
            updateTask(taskId, "FAILED", 100, "专业评测任务失败", e.getMessage(), null, new Date(),
                    System.currentTimeMillis() - startedMs);
        }
    }

    private JSONObject buildPythonBenchmarkPayload(ModelBenchmarkTaskEntity task) {
        List<String> modelIds = parseStringList(task.getModelIds());
        JSONArray models = new JSONArray();
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
        JSONObject payload = new JSONObject();
        payload.set("task_id", task.getId());
        payload.set("model_type", task.getModelType());
        payload.set("iterations", task.getIterations());
        payload.set("concurrency", task.getConcurrency());
        payload.set("models", models);
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

    private ModelBenchmarkTaskDTO toTaskDTO(ModelBenchmarkTaskEntity task) {
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
        dto.setMetricNotes(parseStringList(task.getMetricNotes()));
        dto.setResults(loadResultDTOs(task.getId()));
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
            return dto;
        }).toList();
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

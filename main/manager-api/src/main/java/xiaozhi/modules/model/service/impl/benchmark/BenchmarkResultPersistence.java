package xiaozhi.modules.model.service.impl.benchmark;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.AllArgsConstructor;
import xiaozhi.modules.model.dao.ModelBenchmarkResultDao;
import xiaozhi.modules.model.dao.ModelBenchmarkSampleDao;
import xiaozhi.modules.model.dto.ModelBenchmarkTaskDTO;
import xiaozhi.modules.model.dto.ModelPerformanceTestResultDTO;
import xiaozhi.modules.model.dto.ModelPerformanceTestSampleDTO;
import xiaozhi.modules.model.entity.ModelBenchmarkResultEntity;
import xiaozhi.modules.model.entity.ModelBenchmarkSampleEntity;
import xiaozhi.modules.model.entity.ModelBenchmarkTaskEntity;

@Component
@AllArgsConstructor
public class BenchmarkResultPersistence {

    private final ModelBenchmarkResultDao resultDao;
    private final ModelBenchmarkSampleDao sampleDao;

    public void persistPythonBenchmarkResult(String taskId, JSONObject response) {
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

    public void persistFailedModelResult(String taskId, JSONObject model, String message) {
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

    public ModelBenchmarkTaskDTO toTaskDTO(ModelBenchmarkTaskEntity task, boolean includeResults) {
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

    public long countResults(String taskId) {
        Long count = resultDao.selectCount(new QueryWrapper<ModelBenchmarkResultEntity>().eq("task_id", taskId));
        return count == null ? 0 : count;
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

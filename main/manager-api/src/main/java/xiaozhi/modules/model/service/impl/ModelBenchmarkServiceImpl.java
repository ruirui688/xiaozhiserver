package xiaozhi.modules.model.service.impl;

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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.model.dao.ModelBenchmarkResultDao;
import xiaozhi.modules.model.dao.ModelBenchmarkSampleDao;
import xiaozhi.modules.model.dao.ModelBenchmarkTaskDao;
import xiaozhi.modules.model.dto.ModelBenchmarkCreateResponseDTO;
import xiaozhi.modules.model.dto.ModelBenchmarkTaskDTO;
import xiaozhi.modules.model.dto.ModelPerformanceTestRequestDTO;
import xiaozhi.modules.model.entity.ModelBenchmarkResultEntity;
import xiaozhi.modules.model.entity.ModelBenchmarkSampleEntity;
import xiaozhi.modules.model.entity.ModelBenchmarkTaskEntity;
import xiaozhi.modules.model.service.ModelBenchmarkService;
import xiaozhi.modules.model.service.impl.benchmark.BenchmarkContextBuilder;
import xiaozhi.modules.model.service.impl.benchmark.BenchmarkResultPersistence;
import xiaozhi.modules.model.service.impl.benchmark.PythonBenchmarkClient;

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
            "LLM/SLM 选择真实测试设备时，会按设备 MAC 加载智能体运行时上下文，覆盖人设、知识库、插件、MCP、上下文源和记忆。",
            "未选择真实测试设备时，LLM/SLM 退回候选模型直连评测，只能作为模型基础能力对比。",
            "LLM/SLM 使用小智设备智能体样本和 LLMJudge 六维评分，覆盖人设一致性、知识库事实、插件工具、隐私边界、多轮上下文和语音口语噪声。",
            "LLMJudge 默认使用 DeepSeek/OpenAI Compatible 非推理评审模型；不可用时自动降级为启发式评估，并在报告中标记评审模式。",
            "ASR 关注真实音频识别延时、空识别率、CER/WER；缺少参考文本时不计算准确率。",
            "TTS 关注合成总耗时、首音频耗时、音频有效性和 RTF。",
            "未覆盖的指标显示为未实测，不计入失败，也不修改模型启用状态。");

    private final ModelBenchmarkTaskDao taskDao;
    private final ModelBenchmarkResultDao resultDao;
    private final ModelBenchmarkSampleDao sampleDao;
    private final BenchmarkContextBuilder contextBuilder;
    private final PythonBenchmarkClient pythonBenchmarkClient;
    private final BenchmarkResultPersistence resultPersistence;

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

        JSONObject judgeConfig = contextBuilder.buildJudgeConfig(request);
        if (!judgeConfig.isEmpty()) {
            JUDGE_CONFIGS.put(task.getId(), judgeConfig);
        }
        JSONObject agentContext = contextBuilder.buildAgentContext(request);
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
        return resultPersistence.toTaskDTO(task, true);
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
                .map(task -> resultPersistence.toTaskDTO(task, false))
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
            List<JSONObject> models = contextBuilder.buildPythonBenchmarkModels(task);
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
                            JSONObject payload = contextBuilder.buildPythonBenchmarkPayload(
                                    task, List.of(model), JUDGE_CONFIGS.get(task.getId()), AGENT_CONTEXTS.get(task.getId()));
                            JSONObject response = pythonBenchmarkClient.runBenchmark(payload);
                            resultPersistence.persistPythonBenchmarkResult(task.getId(), response);
                        } catch (Exception e) {
                            failed.incrementAndGet();
                            log.warn("模型专业评测单模型失败，taskId: {}, modelId: {}", task.getId(), model.getStr("id"), e);
                            resultPersistence.persistFailedModelResult(task.getId(), model, e.getMessage());
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
        return "专业评测完成，共 " + resultPersistence.countResults(taskId) + " 个模型";
    }

    private int normalize(Integer value, int defaultValue, int maxValue) {
        if (value == null || value < 1) {
            return defaultValue;
        }
        return Math.min(value, maxValue);
    }

    private String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}

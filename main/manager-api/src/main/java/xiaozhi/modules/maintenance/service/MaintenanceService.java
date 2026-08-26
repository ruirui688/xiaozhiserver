package xiaozhi.modules.maintenance.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.maintenance.dao.MaintenancePolicyDao;
import xiaozhi.modules.maintenance.dao.MaintenanceRunDao;
import xiaozhi.modules.maintenance.dto.HarnessMaintenanceRequest;
import xiaozhi.modules.maintenance.dto.HarnessMaintenanceResponse;
import xiaozhi.modules.maintenance.dto.MaintenancePolicyRequest;
import xiaozhi.modules.maintenance.entity.MaintenancePolicyEntity;
import xiaozhi.modules.maintenance.entity.MaintenanceProposalEntity;
import xiaozhi.modules.maintenance.entity.MaintenanceRunEntity;
import xiaozhi.modules.security.user.SecurityUser;

/** Persistent policy, scheduling, quota and run orchestration. */
@Service
@Slf4j
public class MaintenanceService {
    private static final Set<String> KNOWLEDGE_MODES = Set.of("OFF", "DISCOVERY_REVIEW", "DISCOVERY_AUTO");
    private static final Set<String> MEMORY_MODES = Set.of("OFF", "REVIEW");
    private final MaintenancePolicyDao policyDao;
    private final MaintenanceRunDao runDao;
    private final MaintenanceSnapshotService snapshotService;
    private final MaintenanceProposalService proposalService;
    private final MaintenanceModelResolver modelResolver;
    private final HarnessMaintenanceGateway gateway;
    private final ObjectMapper objectMapper;
    private final AsyncTaskExecutor executor;
    private final MaintenanceRunEventService eventService;

    public MaintenanceService(MaintenancePolicyDao policyDao, MaintenanceRunDao runDao,
            MaintenanceSnapshotService snapshotService, MaintenanceProposalService proposalService,
            MaintenanceModelResolver modelResolver, HarnessMaintenanceGateway gateway, ObjectMapper objectMapper,
            @Qualifier("maintenanceTaskExecutor") AsyncTaskExecutor executor,
            MaintenanceRunEventService eventService) {
        this.policyDao = policyDao;
        this.runDao = runDao;
        this.snapshotService = snapshotService;
        this.proposalService = proposalService;
        this.modelResolver = modelResolver;
        this.gateway = gateway;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.eventService = eventService;
    }

    public MaintenancePolicyEntity getPolicy(String resourceType, String resourceId) {
        MaintenancePolicyEntity policy = findPolicy(resourceType, resourceId);
        if (policy != null) return policy;
        policy = new MaintenancePolicyEntity();
        policy.setResourceType(normalizeType(resourceType));
        policy.setResourceId(resourceId);
        policy.setMode("OFF");
        policy.setScheduleMinutes(360);
        policy.setMaxProposals(8);
        policy.setMaxMonthlyRuns(60);
        policy.setModelConfigId(null);
        policy.setCustomPrompt(null);
        policy.setEnabled(0);
        policy.setTopicsJson("[]");
        policy.setTrustedDomainsJson("[]");
        return policy;
    }

    @Transactional(rollbackFor = Exception.class)
    public MaintenancePolicyEntity savePolicy(String resourceType, String resourceId, String workspaceId,
            MaintenancePolicyRequest request) {
        String type = normalizeType(resourceType);
        validatePolicy(type, request);
        Date now = new Date();
        MaintenancePolicyEntity policy = findPolicy(type, resourceId);
        boolean creating = policy == null;
        if (creating) {
            policy = new MaintenancePolicyEntity();
            policy.setResourceType(type);
            policy.setResourceId(resourceId);
            policy.setCreator(SecurityUser.getUserId());
            policy.setCreatedAt(now);
        }
        policy.setWorkspaceId(workspaceId);
        policy.setMode(request.getMode());
        policy.setTopicsJson(writeJson(cleanList(request.getTopics())));
        policy.setCustomPrompt(StringUtils.trimToNull(request.getCustomPrompt()));
        policy.setTrustedDomainsJson(writeJson(cleanDomains(request.getTrustedDomains())));
        policy.setScheduleMinutes(request.getScheduleMinutes());
        policy.setMaxProposals(request.getMaxProposals());
        policy.setMaxMonthlyRuns(request.getMaxMonthlyRuns());
        policy.setModelConfigId(StringUtils.trimToNull(request.getModelConfigId()));
        boolean enabled = Boolean.TRUE.equals(request.getEnabled()) && !"OFF".equals(request.getMode());
        policy.setEnabled(enabled ? 1 : 0);
        policy.setNextRunAt(enabled ? Date.from(Instant.now().plusSeconds(request.getScheduleMinutes() * 60L)) : null);
        policy.setUpdatedAt(now);
        if (creating) policyDao.insert(policy); else policyDao.updateById(policy);
        return policy;
    }

    public List<MaintenanceRunEntity> listRuns(String resourceType, String resourceId) {
        return runDao.selectList(new QueryWrapper<MaintenanceRunEntity>()
                .eq("resource_type", normalizeType(resourceType)).eq("resource_id", resourceId)
                .orderByDesc("created_at").last("LIMIT 50"));
    }

    public MaintenanceRunEntity getRun(String runId) {
        MaintenanceRunEntity run = runDao.selectById(runId);
        if (run == null) throw new RenException("维护运行记录不存在");
        return run;
    }

    public List<MaintenanceProposalEntity> listProposals(String type, String id) {
        return proposalService.list(normalizeType(type), id);
    }
    public MaintenanceProposalEntity getProposal(String id) { return proposalService.get(id); }
    public MaintenanceProposalEntity approve(String id) { return proposalService.approve(id); }
    public MaintenanceProposalEntity reject(String id) { return proposalService.reject(id); }

    public MaintenanceRunEntity trigger(String resourceType, String resourceId, String triggerType) {
        return trigger(resourceType, resourceId, triggerType, null);
    }

    public MaintenanceRunEntity trigger(String resourceType, String resourceId, String triggerType,
            String userInstruction) {
        MaintenancePolicyEntity policy = findPolicy(resourceType, resourceId);
        if (policy == null || policy.getEnabled() == null || policy.getEnabled() != 1 || "OFF".equals(policy.getMode())) {
            throw new RenException("请先为当前资源启用自主维护策略");
        }
        enforceMonthlyLimit(policy);
        MaintenanceRunEntity run = new MaintenanceRunEntity();
        run.setPolicyId(policy.getId());
        run.setWorkspaceId(policy.getWorkspaceId());
        run.setResourceType(policy.getResourceType());
        run.setResourceId(policy.getResourceId());
        run.setTriggerType(triggerType);
        run.setUserInstruction("MANUAL".equals(triggerType)
                ? StringUtils.abbreviate(StringUtils.trimToNull(userInstruction), 2000) : null);
        run.setStatus("QUEUED");
        run.setProposalCount(0);
        run.setInputChars(0);
        run.setOutputChars(0);
        run.setCreatedAt(new Date());
        runDao.insert(run);
        eventService.record(run.getId(), "DISCOVERY", "INFO",
                StringUtils.isBlank(run.getUserInstruction()) ? "维护任务已进入后台队列" : "已收到维护指令，任务进入后台队列");
        policy.setLastRunAt(new Date());
        policy.setNextRunAt(Date.from(Instant.now().plusSeconds(policy.getScheduleMinutes() * 60L)));
        policy.setUpdatedAt(new Date());
        policyDao.updateById(policy);
        try {
            executor.execute(() -> execute(run.getId()));
        } catch (RejectedExecutionException ex) {
            failRun(run, "维护任务队列已满，请稍后重试");
            throw new RenException("维护任务队列已满，请稍后重试");
        }
        return run;
    }

    @Scheduled(fixedDelayString = "${maintenance.scheduler-delay-ms:60000}")
    public void scheduleDuePolicies() {
        recoverInterruptedWork();
        List<MaintenancePolicyEntity> due = policyDao.selectList(new QueryWrapper<MaintenancePolicyEntity>()
                .eq("enabled", 1).le("next_run_at", new Date()).orderByAsc("next_run_at").last("LIMIT 10"));
        for (MaintenancePolicyEntity policy : due) {
            try { trigger(policy.getResourceType(), policy.getResourceId(), "SCHEDULED"); }
            catch (Exception ex) {
                log.warn("维护策略调度失败: policyId={}, reason={}", policy.getId(), safeError(ex));
            }
        }
    }

    public void execute(String runId) {
        MaintenanceRunEntity run = runDao.selectById(runId);
        if (run == null || !"QUEUED".equals(run.getStatus())) return;
        run.setStatus("RUNNING");
        run.setStartedAt(new Date());
        runDao.updateById(run);
        eventService.record(run.getId(), "DISCOVERY", "INFO", "开始准备当前资源快照");
        try {
            MaintenancePolicyEntity policy = policyDao.selectById(run.getPolicyId());
            if (policy == null || policy.getEnabled() != 1) throw new RenException("维护策略已停用");
            Map<String, Object> snapshot = snapshotService.build(policy);
            eventService.record(run.getId(), "DISCOVERY", "INFO", "资源快照已完成，维护智能体正在联网搜索并筛选候选来源");
            HarnessMaintenanceResponse response = gateway.run(new HarnessMaintenanceRequest(
                    run.getId(), policy.getResourceType(), policy.getResourceId(), policy.getMaxProposals(),
                    policy.getCustomPrompt(), run.getUserInstruction(), snapshot,
                    modelResolver.resolve(policy.getModelConfigId())));
            eventService.record(run.getId(), "DISCOVERY", "INFO", "模型运行完成，正在校验并保存提案");
            run.setProposalCount(proposalService.persist(policy, run, response));
            run.setInputChars(value(response.getInputChars()));
            run.setOutputChars(value(response.getOutputChars()));
            run.setStatus("SUCCEEDED");
            eventService.record(run.getId(), "PROPOSAL", "SUCCESS",
                    "提案生成完成", Map.of("proposalCount", run.getProposalCount(),
                            "inputChars", run.getInputChars(), "outputChars", run.getOutputChars()));
        } catch (Exception ex) {
            run.setStatus("FAILED");
            run.setError(safeError(ex));
            eventService.record(run.getId(), "COMPLETE", "ERROR", run.getError());
        }
        run.setFinishedAt(new Date());
        runDao.updateById(run);
    }

    private void recoverInterruptedWork() {
        Date now = new Date();
        Date stale = new Date(now.getTime() - 15L * 60L * 1000L);
        runDao.update(null, new UpdateWrapper<MaintenanceRunEntity>()
                .in("status", "QUEUED", "RUNNING").lt("created_at", stale)
                .set("status", "FAILED").set("error", "维护进程中断或超时").set("finished_at", now));
        proposalService.recoverInterrupted(stale, now);
    }

    private void validatePolicy(String type, MaintenancePolicyRequest request) {
        Set<String> allowed = "KNOWLEDGE".equals(type) ? KNOWLEDGE_MODES : MEMORY_MODES;
        if (!allowed.contains(request.getMode())) throw new RenException("当前资源不支持该维护模式");
        List<String> domains = cleanDomains(request.getTrustedDomains());
        if ("DISCOVERY_AUTO".equals(request.getMode()) && domains.isEmpty()) {
            throw new RenException("自动采纳模式必须配置可信域名白名单");
        }
        if ("MEMORY".equals(type) && !domains.isEmpty()) throw new RenException("记忆维护禁止配置或使用网页来源");
        if (Boolean.TRUE.equals(request.getEnabled()) && !"OFF".equals(request.getMode())) {
            modelResolver.validate(request.getModelConfigId());
        }
    }

    private void enforceMonthlyLimit(MaintenancePolicyEntity policy) {
        Date start = Date.from(LocalDate.now().withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant());
        Long count = runDao.selectCount(new QueryWrapper<MaintenanceRunEntity>()
                .eq("policy_id", policy.getId()).ge("created_at", start));
        if (count >= policy.getMaxMonthlyRuns()) throw new RenException("本月自主维护运行次数已达上限");
    }

    private MaintenancePolicyEntity findPolicy(String type, String id) {
        return policyDao.selectOne(new QueryWrapper<MaintenancePolicyEntity>()
                .eq("resource_type", normalizeType(type)).eq("resource_id", id));
    }

    private String normalizeType(String type) {
        String value = StringUtils.upperCase(StringUtils.trim(type), Locale.ROOT);
        if (!Set.of("KNOWLEDGE", "MEMORY").contains(value)) throw new RenException("不支持的维护资源类型");
        return value;
    }

    private List<String> cleanList(List<String> values) {
        if (values == null) return List.of();
        return values.stream().map(StringUtils::trim).filter(StringUtils::isNotBlank).distinct().limit(20).toList();
    }

    private List<String> cleanDomains(List<String> values) {
        List<String> domains = cleanList(values).stream().map(item -> StringUtils.lowerCase(item, Locale.ROOT))
                .map(item -> StringUtils.removeStart(item, ".")).toList();
        for (String domain : domains) {
            if (!domain.matches("^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)*[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")) {
                throw new RenException("可信域名格式不正确: " + domain);
            }
        }
        return domains;
    }

    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception ex) { throw new RenException("维护配置序列化失败"); }
    }

    private void failRun(MaintenanceRunEntity run, String error) {
        run.setStatus("FAILED");
        run.setError(error);
        run.setFinishedAt(new Date());
        runDao.updateById(run);
    }

    private String safeError(Throwable throwable) {
        String message = throwable instanceof RenException ? throwable.getMessage() : "维护执行失败";
        return StringUtils.abbreviate(StringUtils.defaultIfBlank(message, "维护执行失败")
                .replaceAll("(?i)(api[_-]?key|authorization|token|secret)\\s*[:=]\\s*\\S+", "$1=***"), 500);
    }

    private int value(Integer number) { return number == null ? 0 : Math.max(number, 0); }
}

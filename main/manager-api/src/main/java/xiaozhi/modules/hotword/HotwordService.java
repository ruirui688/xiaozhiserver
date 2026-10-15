package xiaozhi.modules.hotword;

import java.util.*;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.agent.runtime.AgentRuntimeConfigRefreshEvent;
import xiaozhi.modules.workspace.service.WorkspacePermissionService;

@Service
public class HotwordService {
    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    private final WorkspacePermissionService permission;
    private final HotwordCredentialSources sources;
    private final AgentDao agents;
    private final VolcengineHotwordClient vendor;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;

    public HotwordService(JdbcTemplate db, ObjectMapper mapper, WorkspacePermissionService permission,
            HotwordCredentialSources sources, AgentDao agents, VolcengineHotwordClient vendor,
            ApplicationEventPublisher events, PlatformTransactionManager transactions) {
        this.db = db; this.mapper = mapper; this.permission = permission; this.sources = sources;
        this.agents = agents; this.vendor = vendor; this.events = events;
        this.tx = new TransactionTemplate(transactions);
    }

    public List<Map<String, Object>> list() {
        String workspace = workspace(false);
        var discovery = sources.discover();
        return db.queryForList("SELECT * FROM ai_asr_hotword WHERE workspace_id=? ORDER BY update_date DESC", workspace)
                .stream().map(row -> view(row, discovery)).toList();
    }

    public Map<String, Object> sourceOptions() {
        workspace(false);
        var discovery = sources.discover();
        boolean permitted = HotwordImportService.canImport();
        String status = !permitted ? "NO_PERMISSION" : discovery.sources().isEmpty()
                ? (discovery.unavailable() ? "UNAVAILABLE" : "NOT_CONFIGURED") : "READY";
        String message = switch (status) {
            case "NO_PERMISSION" -> "供应商词库导入尚未接入独立凭据，请使用工作空间热词编辑或批量粘贴词条";
            case "NOT_CONFIGURED" -> "尚未配置火山来源，请联系平台管理员配置豆包语音通道";
            case "UNAVAILABLE" -> "火山配置暂不可用，请管理员检查配置";
            default -> discovery.unavailable() ? "部分火山配置暂不可用" : "";
        };
        return Map.of("sources", discovery.sources().stream()
                .map(s -> Map.of("id", s.id(), "name", s.name(), "available", true)).toList(),
                "capabilities", Map.of("canImportRemote", permitted, "status", status, "message", message));
    }

    public List<Map<String, Object>> agentOptions() {
        return workspaceAgents(workspace(false)).stream().map(a -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", a.getId()); result.put("agentName", a.getAgentName());
            result.put("asrModelId", a.getAsrModelId()); result.put("asrHotwordId", a.getAsrHotwordId());
            String source = sources.forModel(a.getAsrModelId(), true);
            result.put("sourceId", source == null ? "" : source);
            return result;
        }).toList();
    }

    public Map<String, Object> create(HotwordSaveDTO dto) {
        String workspace = workspace(true);
        sources.require(dto.sourceId());
        List<HotwordSaveDTO.Entry> entries = normalize(dto.entries());
        String id = uuid();
        db.update("INSERT INTO ai_asr_hotword(id,workspace_id,name,credential_fingerprint,entries_json) VALUES(?,?,?,?,?)",
                id, workspace, dto.name().trim(), dto.sourceId(), encode(entries));
        return view(require(id, false));
    }

    public Map<String, Object> update(String id, HotwordSaveDTO dto) {
        List<HotwordSaveDTO.Entry> entries = normalize(dto.entries());
        return tx.execute(status -> {
            Map<String, Object> row = require(id, true);
            idle(row);
            if (!Objects.equals(sourceId(row), dto.sourceId())) throw new RenException("词库创建后不能更换火山来源，请新建词库");
            pinDraftSource(row);
            db.update("UPDATE ai_asr_hotword SET name=?,entries_json=?,status='DRAFT',last_error='',update_date=NOW(3) WHERE id=?",
                    dto.name().trim(), encode(entries), id);
            return view(require(id, false));
        });
    }

    public Map<String, Object> publish(String id) {
        String token = uuid();
        Map<String, Object> row = claim(id, token, "PUBLISHING");
        String remoteId = (String) row.get("remote_id");
        try {
            String key = credential(row);
            List<HotwordSaveDTO.Entry> entries = decode(row.get("entries_json"));
            String remoteName = remoteName(id, contents(entries));
            // Register intent before contacting the vendor. A timeout can be recovered by exact name.
            db.update("INSERT IGNORE INTO ai_asr_hotword_revision(remote_name,hotword_id) VALUES(?,?)", remoteName, id);
            String candidate = vendor.find(key, remoteName);
            heartbeat(id, token);
            if (candidate == null) candidate = vendor.create(key, remoteName, contents(entries));
            heartbeat(id, token);
            db.update("UPDATE ai_asr_hotword_revision SET remote_id=? WHERE remote_name=?", candidate, remoteName);
            vendor.verify(key, candidate, entries);
            finish(id, token, candidate, "PUBLISHED", "");
        } catch (RenException error) {
            finish(id, token, remoteId, "FAILED", error.getMessage());
        } catch (RuntimeException error) {
            // No raw transport/database exception text can reach API or storage.
            finish(id, token, remoteId, "FAILED", "发布未完成，请重试核对远端词表");
        }
        return view(require(id, false));
    }

    public void delete(String id) {
        String token = uuid();
        Map<String, Object> row = tx.execute(status -> {
            Map<String, Object> current = require(id, true);
            retryable(current, "DELETING");
            revisionIds(id).forEach(this::ensureUnused);
            db.update("UPDATE ai_asr_hotword SET status='DELETING',operation_token=?,update_date=NOW(3) WHERE id=?", token, id);
            return current;
        });
        try {
            List<Map<String, Object>> revisions = db.queryForList("SELECT * FROM ai_asr_hotword_revision WHERE hotword_id=?", id);
            if (!revisions.isEmpty()) {
                String key = null;
                for (Map<String, Object> revision : revisions) {
                    // Imported originals remain owned by the vendor account, never delete them.
                    if (((String) revision.get("remote_name")).startsWith("import_")) {
                        db.update("DELETE FROM ai_asr_hotword_revision WHERE remote_name=?", revision.get("remote_name"));
                        continue;
                    }
                    if (key == null) key = credential(row);
                    heartbeat(id, token);
                    String found = vendor.find(key, (String) revision.get("remote_name"));
                    heartbeat(id, token);
                    if (found != null) vendor.delete(key, found);
                    db.update("DELETE FROM ai_asr_hotword_revision WHERE remote_name=?", revision.get("remote_name"));
                }
            }
            db.update("DELETE FROM ai_asr_hotword WHERE id=? AND operation_token=?", id, token);
        } catch (RenException error) {
            finish(id, token, (String) row.get("remote_id"), "FAILED", error.getMessage());
            throw error;
        } catch (RuntimeException error) {
            finish(id, token, (String) row.get("remote_id"), "FAILED", "删除未完成，请重试核对远端词表");
            throw new RenException("删除未完成，请重试核对远端词表");
        }
    }

    public Map<String, Object> apply(String id, List<String> agentIds, boolean remove) {
        return tx.execute(status -> {
            Map<String, Object> row = require(id, true);
            idle(row);
            String remoteId = (String) row.get("remote_id");
            Set<String> ownedIds = revisionIds(id);
            if (!remove && (remoteId == null || !"PUBLISHED".equals(row.get("status"))))
                throw new RenException("请先成功发布词库再应用");
            if (!remove) credential(row);
            for (String agentId : new TreeSet<>(agentIds)) {
                agents.lockByIdForStorageQuota(agentId);
                AgentEntity agent = agents.selectById(agentId);
                if (agent == null || !Objects.equals(agent.getWorkspaceId(), row.get("workspace_id")))
                    throw new RenException("智能体不属于当前工作空间");
                permission.requireWrite(agent.getWorkspaceId());
                if (!remove && !Objects.equals(sources.requireForModel(agent.getAsrModelId()), sourceId(row)))
                    throw new RenException("热词库与当前识别模型的平台语音通道来源不一致，请选择同一来源的热词库");
                List<String> candidates = new ArrayList<>(agent.getAsrHotwordIds() == null ? List.of() : agent.getAsrHotwordIds());
                candidates.removeIf(ownedIds::contains);
                String active = agent.getAsrHotwordId();
                if (remove) {
                    if (ownedIds.contains(active)) active = null;
                } else {
                    candidates.add(0, remoteId);
                    if (candidates.size() > 10) candidates = new ArrayList<>(candidates.subList(0, 10));
                    active = remoteId;
                }
                // Update only these columns: do not overwrite unrelated concurrent role changes.
                db.update("UPDATE ai_agent SET asr_hotword_id=?,asr_hotword_ids=? WHERE id=? AND workspace_id=?",
                        active, candidates.isEmpty() ? null : encode(candidates), agentId, row.get("workspace_id"));
                events.publishEvent(AgentRuntimeConfigRefreshEvent.forAgent(agentId, "asr_hotword_updated"));
            }
            return view(require(id, false));
        });
    }

    private Map<String, Object> claim(String id, String token, String state) {
        return tx.execute(status -> {
            Map<String, Object> row = require(id, true);
            retryable(row, state);
            db.update("UPDATE ai_asr_hotword SET status=?,operation_token=?,last_error='',update_date=NOW(3) WHERE id=?", state, token, id);
            return row;
        });
    }

    private void finish(String id, String token, String remoteId, String state, String error) {
        db.update("UPDATE ai_asr_hotword SET remote_id=?,status=?,last_error=?,operation_token=NULL,update_date=NOW(3) WHERE id=? AND operation_token=?",
                remoteId, state, error, id, token);
    }

    private Map<String, Object> require(String id, boolean write) {
        String workspace = workspace(write);
        List<Map<String, Object>> rows = db.queryForList("SELECT * FROM ai_asr_hotword WHERE id=? AND workspace_id=?" + (write ? " FOR UPDATE" : ""), id, workspace);
        if (rows.isEmpty()) throw new RenException("热词库不存在或无权访问");
        return rows.get(0);
    }

    private void idle(Map<String, Object> row) {
        if (Set.of("PUBLISHING", "DELETING").contains(row.get("status"))) {
            throw new RenException("热词库正在同步，请稍后重试原操作");
        }
    }

    private void retryable(Map<String, Object> row, String requestedState) {
        if (requestedState.equals(row.get("status"))) {
            if (operationExpired(row)) return;
        }
        idle(row);
    }

    private void heartbeat(String id, String token) {
        if (db.update("UPDATE ai_asr_hotword SET update_date=NOW(3) WHERE id=? AND operation_token=?", id, token) != 1)
            throw new RenException("操作已被恢复任务接管，请刷新页面");
    }

    private boolean operationExpired(Map<String, Object> row) {
        // DATETIME is returned as LocalDateTime by some drivers. Compare on the DB clock,
        // avoiding both JDBC representation casts and application/database timezone differences.
        Long seconds = db.queryForObject("SELECT TIMESTAMPDIFF(SECOND,update_date,NOW(3)) FROM ai_asr_hotword WHERE id=?",
                Long.class, row.get("id"));
        return seconds != null && seconds > 300;
    }

    String workspace(boolean write) {
        String id = permission.currentWorkspaceId();
        if (write) permission.requireWrite(id); else permission.requireRead(id);
        return id;
    }

    private List<AgentEntity> workspaceAgents(String workspace) {
        return agents.selectList(new LambdaQueryWrapper<AgentEntity>().eq(AgentEntity::getWorkspaceId, workspace));
    }

    Map<String, Object> view(Map<String, Object> row) {
        return view(row, sources.discover());
    }

    private Map<String, Object> view(Map<String, Object> row, HotwordCredentialSources.Discovery discovery) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.get("id")); result.put("name", row.get("name"));
        result.put("remoteId", row.get("remote_id"));
        String sourceId = sourceId(row);
        var source = discovery.sources().stream().filter(s -> s.id().equals(sourceId)).findFirst();
        result.put("sourceId", sourceId == null ? "" : sourceId);
        result.put("sourceName", source.map(HotwordCredentialSources.Source::name).orElse("来源暂不可用"));
        result.put("available", source.isPresent());
        result.put("entries", decode(row.get("entries_json"))); result.put("status", row.get("status"));
        Long imported = db.queryForObject("SELECT COUNT(*) FROM ai_asr_hotword_revision WHERE hotword_id=? AND LEFT(remote_name,7)='import_'", Long.class, row.get("id"));
        result.put("imported", imported != null && imported > 0);
        result.put("lastError", row.get("last_error")); result.put("updateDate", row.get("update_date"));
        result.put("recoverable", Set.of("PUBLISHING", "DELETING").contains(row.get("status"))
                && operationExpired(row));
        Set<String> ownedIds = revisionIds((String) row.get("id"));
        List<AgentEntity> bound = ownedIds.isEmpty() ? List.of() : workspaceAgents((String) row.get("workspace_id")).stream()
                .filter(a -> ownedIds.contains(a.getAsrHotwordId())
                        || (a.getAsrHotwordIds() != null && a.getAsrHotwordIds().stream().anyMatch(ownedIds::contains))).toList();
        result.put("agentIds", bound.stream().map(AgentEntity::getId).toList());
        result.put("agentNames", bound.stream().map(AgentEntity::getAgentName).toList());
        return result;
    }

    private void ensureUnused(String remoteId) {
        Long count = db.queryForObject("SELECT COUNT(*) FROM ai_agent WHERE asr_hotword_id=? OR JSON_CONTAINS(COALESCE(asr_hotword_ids,JSON_ARRAY()),JSON_QUOTE(?))", Long.class, remoteId, remoteId);
        Long templates = db.queryForObject("SELECT COUNT(*) FROM ai_agent_template WHERE asr_hotword_id=? OR JSON_SEARCH(asr_hotword_ids,'one',?) IS NOT NULL OR JSON_SEARCH(snapshot_json,'one',?) IS NOT NULL OR JSON_SEARCH(public_snapshot_json,'one',?) IS NOT NULL", Long.class, remoteId, remoteId, remoteId, remoteId);
        if ((count != null && count > 0) || (templates != null && templates > 0))
            throw new RenException("词库仍被智能体或角色模板引用，请先移除引用");
    }

    private String credential(Map<String, Object> row) {
        String sourceId = pinDraftSource(row);
        return sources.require(sourceId).key();
    }
    String sourceId(Map<String, Object> row) {
        String pinned = (String) row.get("credential_fingerprint");
        if (pinned != null && !pinned.isBlank()) return pinned;
        // Only never-published legacy drafts can safely select their original model's current key.
        if (row.get("remote_id") != null) return null;
        Long revisions = db.queryForObject("SELECT COUNT(*) FROM ai_asr_hotword_revision WHERE hotword_id=?", Long.class, row.get("id"));
        if (revisions != null && revisions > 0) return null;
        return sources.forModel((String) row.get("model_id"), false);
    }
    private String pinDraftSource(Map<String, Object> row) {
        String sourceId = sourceId(row);
        if (sourceId == null) throw new RenException("旧词库缺少可验证的火山来源，请管理员核对原配置后处理");
        if (row.get("credential_fingerprint") == null) {
            int updated = db.update("UPDATE ai_asr_hotword SET credential_fingerprint=? WHERE id=? AND credential_fingerprint IS NULL", sourceId, row.get("id"));
            if (updated != 1) throw new RenException("词库来源已变更，请刷新后重试");
            row.put("credential_fingerprint", sourceId);
        }
        return sourceId;
    }
    private Set<String> revisionIds(String id) {
        return new HashSet<>(db.queryForList("SELECT remote_id FROM ai_asr_hotword_revision WHERE hotword_id=? AND remote_id IS NOT NULL", String.class, id));
    }
    private String remoteName(String id, String contents) {
        return "xz_" + id + "_" + digest(contents).substring(0, 24);
    }
    static String digest(String contents) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(contents.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private String uuid() { return UUID.randomUUID().toString().replace("-", ""); }
    private String encode(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception e) { throw new RenException("热词数据编码失败"); }
    }
    private List<HotwordSaveDTO.Entry> decode(Object value) {
        try { return mapper.readValue(value instanceof byte[] bytes ? new String(bytes, java.nio.charset.StandardCharsets.UTF_8) : String.valueOf(value), new TypeReference<>() {}); }
        catch (Exception e) { throw new RenException("热词数据读取失败"); }
    }
    static List<HotwordSaveDTO.Entry> normalize(List<HotwordSaveDTO.Entry> entries) {
        if (entries == null || entries.isEmpty() || entries.size() > 5000) throw new RenException("热词数量应为 1 至 5000");
        Set<String> seen = new HashSet<>();
        List<HotwordSaveDTO.Entry> result = new ArrayList<>();
        for (HotwordSaveDTO.Entry entry : entries) {
            if (entry == null || entry.word() == null) throw new RenException("热词不能为空");
            String word = entry.word().trim();
            int weight = entry.weight() == null ? 4 : entry.weight();
            if (word.isEmpty() || word.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 30 || word.contains("|") || word.codePoints().anyMatch(Character::isISOControl) || weight < 1 || weight > 10)
                throw new RenException("单条热词最多 30 UTF-8 字节（通常 10 个汉字），不可包含竖线或控制字符，权重为 1 至 10");
            if (!seen.add(word)) throw new RenException("词库包含重复词条，请合并后保存");
            result.add(new HotwordSaveDTO.Entry(word, weight));
        }
        return result;
    }
    static String contents(List<HotwordSaveDTO.Entry> entries) {
        return entries.stream().map(e -> e.word() + "|" + e.weight()).collect(Collectors.joining("\n"));
    }
}

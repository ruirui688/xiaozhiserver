package xiaozhi.modules.hotword;

import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.security.user.SecurityUser;

/** Explicit administrator import: account-wide vendor tables must not leak to ordinary tenants. */
@Service
public class HotwordImportService {
    private final HotwordService hotwords;
    private final VolcengineHotwordClient vendor;
    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    public HotwordImportService(HotwordService hotwords, VolcengineHotwordClient vendor, JdbcTemplate db,
            ObjectMapper mapper, PlatformTransactionManager transactions) {
        this.hotwords = hotwords; this.vendor = vendor; this.db = db; this.mapper = mapper;
        this.tx = new TransactionTemplate(transactions);
    }

    static boolean canImport() {
        var subject = SecurityUser.getSubject();
        return subject != null && subject.isPermitted("sys:role:superAdmin");
    }

    private void requireAdmin() {
        if (!canImport()) throw new RenException("仅平台管理员可读取和导入火山账号已有词库");
    }

    public List<Map<String, Object>> remote(String modelId) {
        requireAdmin();
        String workspace = hotwords.workspace(false);
        String key = hotwords.key(modelId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonNode table : vendor.list(key)) {
            String remoteId = table.path("BoostingTableID").asText();
            if (!remoteId.matches("[A-Za-z0-9_-]{1,128}")) throw new RenException("火山词表标识格式异常");
            Map<String, Object> owner = owner(remoteId);
            if (owner != null && (!workspace.equals(owner.get("workspace_id")) || !modelId.equals(owner.get("model_id")))) continue;
            result.add(Map.of("remoteId", remoteId, "name", table.path("BoostingTableName").asText(remoteId),
                    "wordCount", table.path("WordCount").asInt(), "imported", owner != null,
                    "localId", owner == null ? "" : owner.get("id")));
        }
        return result;
    }

    public Map<String, Object> importRemote(HotwordImportDTO dto) {
        requireAdmin();
        String workspace = hotwords.workspace(true);
        String key = hotwords.key(dto.modelId());
        Map<String, Object> existing = owner(dto.remoteId());
        if (existing != null) return existing(existing, workspace, dto.modelId());
        JsonNode table = vendor.get(key, dto.remoteId());
        List<HotwordSaveDTO.Entry> entries = parseEntries(table);
        String name = table.path("BoostingTableName").asText(dto.remoteId()).trim();
        if (name.isEmpty() || name.length() > 80) throw new RenException("远端词库名称须为 1 至 80 个字符");
        String id = UUID.randomUUID().toString().replace("-", "");
        String encoded;
        try { encoded = mapper.writeValueAsString(entries); }
        catch (Exception e) { throw new RenException("热词编码失败"); }
        try {
            tx.executeWithoutResult(status -> {
                ensureCompatibleReferences(dto, workspace);
                db.update("INSERT INTO ai_asr_hotword(id,workspace_id,name,model_id,entries_json,remote_id,status,credential_fingerprint) VALUES(?,?,?,?,?,?,'PUBLISHED',?)",
                        id, workspace, name, dto.modelId(), encoded, dto.remoteId(), HotwordService.digest(key));
                // A global remote-ID unique index serializes concurrent claims across workspaces/models.
                db.update("INSERT INTO ai_asr_hotword_revision(remote_name,hotword_id,remote_id) VALUES(?,?,?)",
                        "import_" + HotwordService.digest(dto.remoteId()), id, dto.remoteId());
            });
        } catch (DuplicateKeyException e) {
            Map<String, Object> winner = owner(dto.remoteId());
            if (winner == null) throw new RenException("词库正在导入，请稍后刷新");
            return existing(winner, workspace, dto.modelId());
        }
        return existing(Objects.requireNonNull(owner(dto.remoteId())), workspace, dto.modelId());
    }

    private Map<String, Object> owner(String remoteId) {
        var rows = db.queryForList("SELECT h.* FROM ai_asr_hotword h JOIN ai_asr_hotword_revision r ON r.hotword_id=h.id WHERE r.remote_id=?", remoteId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> existing(Map<String, Object> row, String workspace, String modelId) {
        if (!workspace.equals(row.get("workspace_id")) || !modelId.equals(row.get("model_id")))
            throw new RenException("该远端词库已由其他工作空间或识别模型管理");
        if ("DELETING".equals(row.get("status"))) throw new RenException("词库正在删除，请稍后重试");
        return hotwords.view(row);
    }

    private void ensureCompatibleReferences(HotwordImportDTO dto, String workspace) {
        // Do not claim an old manually configured ID if doing so would invalidate other users' settings.
        Long agents = db.queryForObject("SELECT COUNT(*) FROM ai_agent WHERE (asr_hotword_id=? OR JSON_CONTAINS(COALESCE(asr_hotword_ids,JSON_ARRAY()),JSON_QUOTE(?))) AND (NOT(workspace_id <=> ?) OR NOT(asr_model_id <=> ?))",
                Long.class, dto.remoteId(), dto.remoteId(), workspace, dto.modelId());
        Long templates = db.queryForObject("SELECT COUNT(*) FROM ai_agent_template WHERE (asr_hotword_id=? OR JSON_SEARCH(asr_hotword_ids,'one',?) IS NOT NULL OR JSON_SEARCH(snapshot_json,'one',?) IS NOT NULL OR JSON_SEARCH(public_snapshot_json,'one',?) IS NOT NULL) AND (NOT(workspace_id <=> ?) OR NOT(asr_model_id <=> ?))",
                Long.class, dto.remoteId(), dto.remoteId(), dto.remoteId(), dto.remoteId(), workspace, dto.modelId());
        if ((agents != null && agents > 0) || (templates != null && templates > 0))
            throw new RenException("该词库正被其他工作空间或识别模型引用，无法认领；请先统一原有引用");
    }

    static List<HotwordSaveDTO.Entry> parseEntries(JsonNode table) {
        JsonNode file = table.path("File");
        String text;
        if (file.isTextual()) text = file.asText();
        else {
            JsonNode preview = table.path("Preview");
            if (!preview.isArray()) throw new RenException("火山未返回完整词表");
            List<String> lines = new ArrayList<>();
            for (JsonNode word : preview) {
                if (!word.isTextual() || word.asText().contains("\n") || word.asText().contains("\r"))
                    throw new RenException("远端词条格式异常");
                lines.add(word.asText());
            }
            // Some API versions omit File and weights; default 4 is used only for future publications.
            text = String.join("\n", lines);
        }
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        List<HotwordSaveDTO.Entry> entries = new ArrayList<>();
        for (String line : text.split("\\R")) {
            if (line.isBlank()) continue;
            String[] parts = line.split("\\|", -1);
            if (parts.length > 2) throw new RenException("远端词条格式异常");
            int weight = 4;
            if (parts.length == 2) {
                try { weight = Integer.parseInt(parts[1].trim()); }
                catch (NumberFormatException e) { throw new RenException("远端词条权重格式异常"); }
            }
            entries.add(new HotwordSaveDTO.Entry(parts[0], weight));
        }
        if (table.path("WordCount").asInt(-1) != entries.size()) throw new RenException("远端词条数量不完整，导入已停止");
        return HotwordService.normalize(entries);
    }
}

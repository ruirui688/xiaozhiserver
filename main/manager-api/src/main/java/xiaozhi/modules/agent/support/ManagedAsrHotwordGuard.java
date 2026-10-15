package xiaozhi.modules.agent.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

import org.apache.commons.lang3.StringUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.hotword.HotwordCredentialSources;

/** Applies managed-resource isolation to legacy role and template update paths. */
@Component
@RequiredArgsConstructor
public class ManagedAsrHotwordGuard {
    private final JdbcTemplate jdbc;
    private final HotwordCredentialSources sources;

    public void requireAllowed(String workspaceId, String modelId, String activeId, List<String> candidates) {
        TreeSet<String> ids = new TreeSet<>();
        if (StringUtils.isNotBlank(activeId)) {
            ids.add(activeId.trim());
        }
        if (candidates != null) {
            candidates.stream().filter(StringUtils::isNotBlank).map(String::trim).forEach(ids::add);
        }
        if (ids.isEmpty()) {
            return;
        }
        // Called inside the agent update transaction; lock owned references against deletion.
        // Unknown IDs remain compatible with historical manually registered vendor tables.
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        List<String> parameters = new ArrayList<>(ids);
        parameters.addAll(ids);
        var rows = jdbc.queryForList(
                "SELECT h.workspace_id, h.credential_fingerprint, h.status FROM ai_asr_hotword h "
                        + "LEFT JOIN ai_asr_hotword_revision r ON r.hotword_id=h.id "
                        + "WHERE h.remote_id IN (" + placeholders + ") OR r.remote_id IN ("
                        + placeholders + ") ORDER BY h.id FOR UPDATE", parameters.toArray());
        for (var row : rows) {
            if ("DELETING".equals(row.get("status"))) {
                throw new RenException("热词库正在删除，请重新选择热词库");
            }
            if (!Objects.equals(workspaceId, row.get("workspace_id"))) {
                throw new RenException("无权使用其他工作空间的热词库");
            }
        }
        if (rows.isEmpty()) return;
        String modelSource = sources.requireForModel(modelId);
        for (var row : rows) {
            String sourceId = (String) row.get("credential_fingerprint");
            if (sourceId == null || sourceId.isBlank()) {
                throw new RenException("热词库缺少可验证的火山来源，请联系管理员核对原词库配置");
            }
            if (!sourceId.equals(modelSource)) {
                throw new RenException("热词库与当前识别模型的平台语音通道来源不一致，请选择同一来源的热词库");
            }
        }
    }
}

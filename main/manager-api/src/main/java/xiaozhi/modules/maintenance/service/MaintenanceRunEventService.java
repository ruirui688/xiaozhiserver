package xiaozhi.modules.maintenance.service;

import java.util.Date;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import xiaozhi.modules.maintenance.dao.MaintenanceRunEventDao;
import xiaozhi.modules.maintenance.entity.MaintenanceRunEventEntity;

/** Persists bounded, sanitized operational milestones for the web runtime monitor. */
@Service
@AllArgsConstructor
public class MaintenanceRunEventService {
    private final MaintenanceRunEventDao eventDao;
    private final ObjectMapper objectMapper;

    public void record(String runId, String phase, String level, String message) {
        record(runId, phase, level, message, Map.of());
    }

    public void record(String runId, String phase, String level, String message, Map<String, ?> detail) {
        if (StringUtils.isBlank(runId)) return;
        MaintenanceRunEventEntity event = new MaintenanceRunEventEntity();
        event.setRunId(runId);
        event.setPhase(normalize(phase, "DISCOVERY", 32));
        event.setLevel(normalize(level, "INFO", 16));
        event.setMessage(sanitize(message));
        event.setDetailJson(writeDetail(detail));
        event.setCreatedAt(new Date());
        eventDao.insert(event);
    }

    public List<MaintenanceRunEventEntity> list(String runId, Long afterId) {
        QueryWrapper<MaintenanceRunEventEntity> query = new QueryWrapper<MaintenanceRunEventEntity>()
                .eq("run_id", runId);
        if (afterId != null && afterId > 0) query.gt("id", afterId);
        query.orderByAsc("id").last("LIMIT 300");
        return eventDao.selectList(query);
    }

    private String writeDetail(Map<String, ?> detail) {
        if (detail == null || detail.isEmpty()) return null;
        try {
            return StringUtils.abbreviate(objectMapper.writeValueAsString(detail), 2000);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String sanitize(String value) {
        String safe = StringUtils.defaultIfBlank(value, "运行状态已更新")
                .replaceAll("(?i)Bearer\\s+[^\\s,;，；]+", "Bearer ***")
                .replaceAll("(?i)(api[_-]?key|authorization|token|secret|password)\\s*[:=]\\s*(?:Bearer\\s+)?[^\\s,;，；]+", "$1=***");
        return StringUtils.abbreviate(safe, 500);
    }

    private String normalize(String value, String fallback, int maxLength) {
        return StringUtils.abbreviate(StringUtils.upperCase(StringUtils.defaultIfBlank(value, fallback)), maxLength);
    }
}

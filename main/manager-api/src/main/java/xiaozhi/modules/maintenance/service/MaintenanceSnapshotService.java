package xiaozhi.modules.maintenance.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.knowledge.dto.KnowledgeBaseDTO;
import xiaozhi.modules.knowledge.service.KnowledgeBaseService;
import xiaozhi.modules.knowledge.source.service.KnowledgeSourceService;
import xiaozhi.modules.maintenance.entity.MaintenancePolicyEntity;
import xiaozhi.modules.memory.dao.MemoryFactDao;
import xiaozhi.modules.memory.entity.MemoryFactEntity;

@Service
@AllArgsConstructor
public class MaintenanceSnapshotService {
    private static final int MAX_MEMORY_CHARS = 40_000;
    private final MaintenanceResourceAccessService resourceAccessService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeSourceService knowledgeSourceService;
    private final MemoryFactDao memoryFactDao;
    private final ObjectMapper objectMapper;

    public Map<String, Object> build(MaintenancePolicyEntity policy) {
        String workspace = resourceAccessService.resolveWorkspace(policy.getResourceType(), policy.getResourceId());
        if (!policy.getWorkspaceId().equals(workspace)) throw new RenException("资源归属已变化，维护停止");
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("topics", readList(policy.getTopicsJson()));
        input.put("trustedDomains", readList(policy.getTrustedDomainsJson()));
        if ("KNOWLEDGE".equals(policy.getResourceType())) {
            KnowledgeBaseDTO dataset = knowledgeBaseService.getByDatasetId(policy.getResourceId());
            if (dataset == null) throw new RenException("知识库不存在");
            input.put("name", dataset.getName());
            input.put("description", StringUtils.abbreviate(dataset.getDescription(), 2000));
            input.put("existingSources", knowledgeSourceService.list(policy.getResourceId()).stream()
                    .map(item -> Map.of("name", item.getName(), "sourceUri", item.getSourceUri())).toList());
            return input;
        }
        input.put("facts", activeFacts(policy.getResourceId()));
        return input;
    }

    private List<Map<String, Object>> activeFacts(String agentId) {
        List<Map<String, Object>> result = new ArrayList<>();
        int chars = 0;
        for (MemoryFactEntity fact : memoryFactDao.selectList(new QueryWrapper<MemoryFactEntity>()
                .eq("agent_id", agentId).eq("status", "active").orderByDesc("updated_at").last("LIMIT 200"))) {
            String text = StringUtils.abbreviate(StringUtils.defaultString(fact.getCanonicalText()), 2000);
            if (chars + text.length() > MAX_MEMORY_CHARS) break;
            chars += text.length();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("memoryId", fact.getMemoryId());
            item.put("memoryType", fact.getMemoryType());
            item.put("category", fact.getCategory());
            item.put("text", text);
            item.put("importance", fact.getImportance());
            item.put("confidence", fact.getConfidence());
            item.put("updatedAt", fact.getUpdatedAt());
            result.add(item);
        }
        return result;
    }

    private List<String> readList(String json) {
        if (StringUtils.isBlank(json)) return List.of();
        try { return objectMapper.readValue(json, new TypeReference<List<String>>() { }); }
        catch (Exception ex) { return List.of(); }
    }
}

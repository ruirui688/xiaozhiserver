package xiaozhi.modules.maintenance.service;

import java.util.Locale;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import lombok.AllArgsConstructor;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.agent.service.AgentService;
import xiaozhi.modules.agent.vo.AgentInfoVO;
import xiaozhi.modules.knowledge.dto.KnowledgeBaseDTO;
import xiaozhi.modules.knowledge.service.KnowledgeBaseService;
import xiaozhi.modules.knowledge.service.KnowledgeResourcePermissionService;
import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.modules.workspace.service.WorkspacePermissionService;

@Service
@AllArgsConstructor
public class MaintenanceResourceAccessService {
    private final KnowledgeBaseService knowledgeBaseService;
    private final AgentService agentService;
    private final WorkspacePermissionService workspacePermissionService;
    private final KnowledgeResourcePermissionService knowledgePermissionService;

    public String resolveWorkspace(String resourceType, String resourceId) {
        String type = normalizeType(resourceType);
        if ("KNOWLEDGE".equals(type)) {
            KnowledgeBaseDTO dataset = knowledgeBaseService.getByDatasetId(resourceId);
            if (dataset == null) throw new RenException("知识库不存在");
            return dataset.getWorkspaceId();
        }
        if ("MEMORY".equals(type)) {
            AgentInfoVO agent = agentService.getAgentById(resourceId);
            if (agent == null) throw new RenException("智能体不存在");
            return agent.getWorkspaceId();
        }
        throw new RenException("不支持的维护资源类型");
    }

    public String requireRead(String resourceType, String resourceId) {
        if ("KNOWLEDGE".equals(normalizeType(resourceType))) {
            KnowledgeBaseDTO dataset = knowledgeBaseService.getByDatasetId(resourceId);
            knowledgePermissionService.requireRead(dataset);
            return dataset.getWorkspaceId();
        }
        if ("MEMORY".equals(normalizeType(resourceType))
                && !agentService.checkAgentPermission(resourceId, SecurityUser.getUserId())) {
            throw new RenException("无权访问该智能体");
        }
        String workspaceId = resolveWorkspace(resourceType, resourceId);
        workspacePermissionService.requireRead(workspaceId);
        return workspaceId;
    }

    public String requireWrite(String resourceType, String resourceId) {
        if ("KNOWLEDGE".equals(normalizeType(resourceType))) {
            KnowledgeBaseDTO dataset = knowledgeBaseService.getByDatasetId(resourceId);
            knowledgePermissionService.requireAutomatedMaintenance(dataset);
            return dataset.getWorkspaceId();
        }
        if ("MEMORY".equals(normalizeType(resourceType))
                && !agentService.checkAgentPermission(resourceId, SecurityUser.getUserId())) {
            throw new RenException("无权维护该智能体记忆");
        }
        String workspaceId = resolveWorkspace(resourceType, resourceId);
        workspacePermissionService.requireWrite(workspaceId);
        return workspaceId;
    }

    private String normalizeType(String resourceType) {
        String type = StringUtils.upperCase(StringUtils.trim(resourceType), Locale.ROOT);
        if (!"KNOWLEDGE".equals(type) && !"MEMORY".equals(type)) {
            throw new RenException("不支持的维护资源类型");
        }
        return type;
    }
}

package xiaozhi.modules.knowledge.service;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import lombok.AllArgsConstructor;
import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.knowledge.dto.KnowledgeBaseDTO;
import xiaozhi.modules.workspace.service.OfficialResourcePermissionService;
import xiaozhi.modules.workspace.service.WorkspacePermissionService;

@Service
@AllArgsConstructor
public class KnowledgeResourcePermissionService {
    public static final String SCOPE_WORKSPACE = "WORKSPACE";
    public static final String SCOPE_OFFICIAL = "OFFICIAL";

    private final WorkspacePermissionService workspacePermissionService;
    private final OfficialResourcePermissionService officialPermissionService;

    public void requireRead(KnowledgeBaseDTO knowledgeBase) {
        requireExisting(knowledgeBase);
        if (isOfficial(knowledgeBase)) {
            return;
        }
        workspacePermissionService.requireRead(knowledgeBase.getWorkspaceId());
    }

    public void requireWrite(KnowledgeBaseDTO knowledgeBase) {
        requireExisting(knowledgeBase);
        if (isOfficial(knowledgeBase)) {
            officialPermissionService.requireOfficialMaintainer();
            return;
        }
        workspacePermissionService.requireWrite(knowledgeBase.getWorkspaceId());
    }

    public void requireAutomatedMaintenance(KnowledgeBaseDTO knowledgeBase) {
        requireExisting(knowledgeBase);
        if (!isOfficial(knowledgeBase)) {
            throw noPermission("自主维护只允许用于官方知识库");
        }
        officialPermissionService.requireOfficialMaintainer();
    }

    public boolean canWrite(KnowledgeBaseDTO knowledgeBase) {
        if (knowledgeBase == null) {
            return false;
        }
        return isOfficial(knowledgeBase)
                ? officialPermissionService.isOfficialMaintainer()
                : workspacePermissionService.canWrite(knowledgeBase.getWorkspaceId());
    }

    public boolean isOfficial(KnowledgeBaseDTO knowledgeBase) {
        return knowledgeBase != null && SCOPE_OFFICIAL.equalsIgnoreCase(knowledgeBase.getScope());
    }

    public boolean isOfficialMaintainer() {
        return officialPermissionService.isOfficialMaintainer();
    }

    private void requireExisting(KnowledgeBaseDTO knowledgeBase) {
        if (knowledgeBase == null || StringUtils.isBlank(knowledgeBase.getDatasetId())) {
            throw new RenException(ErrorCode.Knowledge_Base_RECORD_NOT_EXISTS);
        }
    }

    private RenException noPermission(String message) {
        RenException exception = new RenException(message);
        exception.setCode(ErrorCode.NO_PERMISSION);
        return exception;
    }
}

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
    public static final String SCOPE_OFFICIAL_DRAFT = "OFFICIAL_DRAFT";

    private final WorkspacePermissionService workspacePermissionService;
    private final OfficialResourcePermissionService officialPermissionService;

    public void requireRead(KnowledgeBaseDTO knowledgeBase) {
        requireExisting(knowledgeBase);
        if (SCOPE_OFFICIAL_DRAFT.equals(knowledgeBase.getScope())) {
            officialPermissionService.requireOfficialMaintainer();
            return;
        }
        if (isOfficial(knowledgeBase)) {
            return;
        }
        workspacePermissionService.requireRead(knowledgeBase.getWorkspaceId());
    }

    public void requireWrite(KnowledgeBaseDTO knowledgeBase) {
        requireExisting(knowledgeBase);
        if (isOfficial(knowledgeBase)) {
            throw noPermission("已发布官方知识库不可原地修改，请创建新草案并审批发布");
        }
        if (SCOPE_OFFICIAL_DRAFT.equals(knowledgeBase.getScope())) {
            throw noPermission("官方草案只能通过运营草案入口修改");
        }
        workspacePermissionService.requireWrite(knowledgeBase.getWorkspaceId());
    }

    public void requireAutomatedMaintenance(KnowledgeBaseDTO knowledgeBase) {
        requireExisting(knowledgeBase);
        if (isOfficial(knowledgeBase) || SCOPE_OFFICIAL_DRAFT.equals(knowledgeBase.getScope())) {
            throw noPermission("官方知识库必须经过独立审批发布，不支持原地自动维护");
        }
        if (!isOfficial(knowledgeBase)) {
            workspacePermissionService.requireWrite(knowledgeBase.getWorkspaceId());
        }
        if (!officialPermissionService.isOfficialMaintainer()) {
            throw noPermission("缺少独立官方内容维护权限");
        }
    }

    public boolean canManageAutomatedMaintenance(KnowledgeBaseDTO knowledgeBase) {
        if (knowledgeBase == null || !officialPermissionService.isOfficialMaintainer()) {
            return false;
        }
        return !isOfficial(knowledgeBase) && !SCOPE_OFFICIAL_DRAFT.equals(knowledgeBase.getScope()) && workspacePermissionService.canWrite(knowledgeBase.getWorkspaceId());
    }

    public boolean canWrite(KnowledgeBaseDTO knowledgeBase) {
        if (knowledgeBase == null) {
            return false;
        }
        return isOfficial(knowledgeBase) || SCOPE_OFFICIAL_DRAFT.equals(knowledgeBase.getScope())
                ? false
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

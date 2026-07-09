package xiaozhi.modules.workspace.service.impl;

import org.springframework.stereotype.Service;

import lombok.AllArgsConstructor;
import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.modules.workspace.service.WorkspacePermissionService;
import xiaozhi.modules.workspace.service.WorkspaceService;

@Service
@AllArgsConstructor
public class WorkspacePermissionServiceImpl implements WorkspacePermissionService {
    private final WorkspaceService workspaceService;

    @Override
    public String currentWorkspaceId() {
        return workspaceService.getCurrentWorkspaceEntity().getId();
    }

    @Override
    public boolean canRead(String workspaceId) {
        return workspaceService.canRead(workspaceId, SecurityUser.getUserId());
    }

    @Override
    public boolean canWrite(String workspaceId) {
        return workspaceService.canWrite(workspaceId, SecurityUser.getUserId());
    }

    @Override
    public void requireRead(String workspaceId) {
        workspaceService.requireRead(workspaceId);
    }

    @Override
    public void requireWrite(String workspaceId) {
        workspaceService.requireWrite(workspaceId);
    }
}

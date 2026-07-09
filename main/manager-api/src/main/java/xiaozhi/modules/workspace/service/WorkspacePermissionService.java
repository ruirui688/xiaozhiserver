package xiaozhi.modules.workspace.service;

public interface WorkspacePermissionService {
    String currentWorkspaceId();

    boolean canRead(String workspaceId);

    boolean canWrite(String workspaceId);

    void requireRead(String workspaceId);

    void requireWrite(String workspaceId);
}

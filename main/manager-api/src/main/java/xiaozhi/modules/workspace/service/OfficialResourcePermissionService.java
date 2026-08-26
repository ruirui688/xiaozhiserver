package xiaozhi.modules.workspace.service;

public interface OfficialResourcePermissionService {
    boolean isOfficialMaintainer();

    void requireOfficialMaintainer();
}

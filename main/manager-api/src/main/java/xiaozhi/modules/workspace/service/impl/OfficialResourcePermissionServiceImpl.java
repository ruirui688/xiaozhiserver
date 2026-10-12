package xiaozhi.modules.workspace.service.impl;

import org.springframework.stereotype.Service;
import xiaozhi.modules.security.ops.OpsAccess;
import xiaozhi.modules.workspace.service.OfficialResourcePermissionService;

@Service
public class OfficialResourcePermissionServiceImpl implements OfficialResourcePermissionService {
    @Override public boolean isOfficialMaintainer() {
        return OpsAccess.has("official.resource.write", "PLATFORM", "official-resources");
    }
    @Override public void requireOfficialMaintainer() {
        OpsAccess.require("official.resource.write", "PLATFORM", "official-resources");
    }
}

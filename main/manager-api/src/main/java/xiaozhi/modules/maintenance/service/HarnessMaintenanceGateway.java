package xiaozhi.modules.maintenance.service;

import xiaozhi.modules.maintenance.dto.HarnessMaintenanceRequest;
import xiaozhi.modules.maintenance.dto.HarnessMaintenanceResponse;

public interface HarnessMaintenanceGateway {
    HarnessMaintenanceResponse run(HarnessMaintenanceRequest request);
}

package xiaozhi.modules.maintenance.service;

import xiaozhi.modules.maintenance.dto.HarnessConsoleLaunchRequest;
import xiaozhi.modules.maintenance.dto.HarnessConsoleLaunchResponse;

public interface HarnessConsoleGateway {
    HarnessConsoleLaunchResponse launch(HarnessConsoleLaunchRequest request);
}

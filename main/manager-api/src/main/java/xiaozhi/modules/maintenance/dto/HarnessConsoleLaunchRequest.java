package xiaozhi.modules.maintenance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/** Internal launch envelope. Secrets are sent only to the isolated maintenance service. */
@Data
@AllArgsConstructor
public class HarnessConsoleLaunchRequest {
    private HarnessMaintenanceRequest maintenanceRequest;
    private String callbackUrl;
    private String callbackSecret;
}

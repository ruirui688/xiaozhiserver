package xiaozhi.modules.maintenance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/** Internal launch envelope. Secrets only enter the isolated maintenance service. */
@Data
@AllArgsConstructor
public class HarnessConsoleLaunchRequest {
    private HarnessMaintenanceRequest maintenanceRequest;
    private String callbackUrl;
    private String callbackSecret;
    private String callbackRunToken;
}

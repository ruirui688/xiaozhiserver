package xiaozhi.modules.maintenance.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.Data;

@Data
public class HarnessMaintenanceResponse {
    private List<Map<String, Object>> proposals = new ArrayList<>();
    private Integer inputChars;
    private Integer outputChars;
}

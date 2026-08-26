package xiaozhi.modules.maintenance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class HarnessModelConfig {
    private String apiKey;
    private String baseUrl;
    private String model;
}

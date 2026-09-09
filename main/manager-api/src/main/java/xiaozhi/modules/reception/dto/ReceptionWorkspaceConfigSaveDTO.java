package xiaozhi.modules.reception.dto;

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ReceptionWorkspaceConfigSaveDTO {
    @Size(max = 120)
    private String companyName;
    @Size(max = 60)
    private String companyIntroText;
    @Size(max = 100)
    private List<Map<String, Object>> faq;
    @Size(max = 200)
    private List<Map<String, Object>> products;
    @Size(max = 100)
    private List<Map<String, Object>> seats;
    private Map<String, Object> stageShow;
    @Size(max = 200)
    private String serviceContactText;
    private Integer status;
}

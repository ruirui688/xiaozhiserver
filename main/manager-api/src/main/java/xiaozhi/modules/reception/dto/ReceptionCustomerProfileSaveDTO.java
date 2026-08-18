package xiaozhi.modules.reception.dto;

import java.util.List;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ReceptionCustomerProfileSaveDTO {
    @Pattern(regexp = "NEW|RETURNING|LONG_TERM|VIP")
    private String customerStage;
    @Size(max = 120)
    private String companyName;
    @Size(max = 20)
    private List<@Size(max = 60) String> hobbies;
    @Size(max = 30)
    private List<@Size(max = 80) String> foodPreferences;
    @Size(max = 20)
    private List<@Size(max = 80) String> seatPreferences;
    @Size(max = 500)
    private String receptionNotes;
    @Size(max = 500)
    private String sensitiveNotes;
    @Size(max = 5)
    private List<@Pattern(regexp = "RECEPTION|RECOMMENDATION|MEMORY|SENSITIVE") String> consentScopes;
    private Integer status;
}

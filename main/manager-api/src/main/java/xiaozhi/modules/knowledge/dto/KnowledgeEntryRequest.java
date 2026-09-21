package xiaozhi.modules.knowledge.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class KnowledgeEntryRequest {
    @Size(max = 20000)
    private String content;
    @Size(max = 4000)
    private String question;
    @Size(max = 16000)
    private String answer;
    @NotNull @Min(0) @Max(1)
    private Integer enabled = 1;
    @Min(0)
    private Long expectedVersion;

}

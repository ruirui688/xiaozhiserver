package xiaozhi.modules.knowledge.dto;

import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class KnowledgePublicationDTO {
    @Pattern(regexp = "WORKSPACE|OFFICIAL", message = "知识库范围仅支持 WORKSPACE 或 OFFICIAL")
    private String scope;
}

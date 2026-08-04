package xiaozhi.modules.agent.dto;

import java.util.ArrayList;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AgentTemplateSaveAsDTO {
    @NotBlank
    @Size(max = 64)
    private String templateName;

    @Valid
    @NotNull
    private AgentUpdateDTO config;

    @Size(max = 50)
    private List<@NotBlank @Size(max = 64) String> tagNames = new ArrayList<>();
}

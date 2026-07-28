package xiaozhi.modules.agent.skill.dto;

import java.util.ArrayList;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AgentSemanticSkillOrderDTO {
    @Valid
    @NotEmpty
    @Size(max = 50)
    private List<OrderItem> items = new ArrayList<>();

    @Data
    public static class OrderItem {
        @NotBlank
        private String id;

        @Min(0)
        @Max(1_000_000)
        private Integer sort;
    }
}

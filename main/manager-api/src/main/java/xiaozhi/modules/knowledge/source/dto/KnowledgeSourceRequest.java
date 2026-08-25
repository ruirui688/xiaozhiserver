package xiaozhi.modules.knowledge.source.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class KnowledgeSourceRequest {
    @NotBlank
    @Size(max = 100)
    private String name;

    @NotBlank
    @Pattern(regexp = "WEB_PAGE", message = "当前仅支持 WEB_PAGE 数据源")
    private String sourceType;

    @NotBlank
    @Size(max = 2048)
    private String sourceUri;

    @Min(5)
    @Max(10080)
    private Integer refreshIntervalMinutes = 30;

    private Boolean enabled = true;
}

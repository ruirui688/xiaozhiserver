package xiaozhi.modules.memory.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "长期事实记忆编辑")
public class MemoryFactDTO {
    private String memoryType;
    private String category;
    private String canonicalText;
    private String payload;
    private BigDecimal importance;
    private BigDecimal confidence;
    private String sensitivity;
}

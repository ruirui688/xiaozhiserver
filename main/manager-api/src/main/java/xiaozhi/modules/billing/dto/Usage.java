package xiaozhi.modules.billing.dto;

import java.math.BigDecimal;
import jakarta.validation.constraints.*;
import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Usage {
    private String serviceType;
    @NotBlank @Pattern(regexp = "[a-z][a-z0-9_]{0,63}")
    private String metric;
    @NotBlank @Pattern(regexp = "[a-z][a-z0-9_]{0,31}")
    private String unit;
    @NotBlank @Pattern(regexp = "PROVIDER|OBSERVED|LOCAL")
    private String source;
    @NotNull @DecimalMin("0") @Digits(integer = 20, fraction = 9)
    private BigDecimal quantity;
}

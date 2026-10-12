package xiaozhi.modules.billing.dto;

import java.time.Instant;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class BillingBindingRequest {
    @Size(max=64) private String id;
    @NotBlank @Pattern(regexp="VOLCENGINE|DEEPSEEK") private String provider;
    @NotBlank @Size(max=128) private String accountRef;
    @NotBlank @Pattern(regexp="PROJECT|ACCOUNT|RESOURCE") private String scopeType;
    @NotBlank @Size(max=255) private String scopeValue;
    @NotBlank @Size(max=64) private String workspaceId;
    @Size(max=64) private String agentId;
    @Size(max=64) private String deviceId;
    @NotNull private Instant validFrom;
    private Instant validTo;
    @Size(max=500) private String note;
}

package xiaozhi.modules.billing.dto;

import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class UsageBatchRequest {
    @NotEmpty @Size(max = 100) @Valid private List<UsageEventRequest> events;
}

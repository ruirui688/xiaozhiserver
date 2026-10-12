package xiaozhi.modules.billing.dto;

import java.time.Instant;
import java.util.List;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class BillingCollectorHealthRequest {
    @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,64}") private String instanceId;
    @NotNull private Instant startedAt;
    @NotBlank @Size(max=32) private String collectorVersion;
    private boolean managedMode, queueAvailable;
    @NotNull @Size(max=64) private List<@Pattern(regexp="[A-Za-z0-9_./:-]{1,64}") String> supportedAdapters=List.of();
    private Instant lastCallStartedAt, lastDeliveryAt, lastContextAt;
    @Min(0) private long activeCount, pendingCount, rejectedCount, persistenceFailureCount, missingContextCalls, missingContextCount, contextCount;
    @Pattern(regexp="DELIVERY_FAILED|INVALID_ACK|ACK_INCOMPLETE") private String lastDeliveryErrorCode;
}

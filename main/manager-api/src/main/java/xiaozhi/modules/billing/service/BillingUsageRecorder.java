package xiaozhi.modules.billing.service;

import java.time.Instant;
import java.util.List;
import lombok.*;
import xiaozhi.modules.billing.dto.Usage;

/** Records only metering facts; credentials, prompts and response text never enter this API. */
public interface BillingUsageRecorder {
    void recordInternal(InternalUsage record);

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    class InternalUsage {
        private String eventId, workspaceId, agentId, deviceId, modelId, provider, serviceType, purpose;
        private String sessionId, turnId, providerRequestId, status;
        private Instant startedAt, endedAt;
        private Boolean usageComplete;
        private List<Usage> usage;
    }
}

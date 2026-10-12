package xiaozhi.modules.billing.dto;

import java.time.Instant;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class UsageEventRequest {
    @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{8,64}") private String eventId;
    @NotBlank @Size(max = 64) private String contextId;
    @NotBlank @Size(max = 128) private String contextToken;
    @NotBlank @Size(max = 64) private String modelId;
    @Size(max = 64) private String provider;
    @NotBlank @Pattern(regexp = "LLM|ASR|TTS|REALTIME|EMBEDDING|OTHER|VLLM|SLM") private String serviceType;
    @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9_-]{0,63}") private String purpose;
    @Size(max = 128) private String sessionId;
    @Size(max = 128) private String turnId;
    @Size(max = 128) private String sentenceId;
    @Size(max = 128) private String attemptId;
    @Size(max = 128) private String logicalCallId;
    @Size(max = 256) private String providerRequestId;
    @NotNull private Instant startedAt;
    @NotNull private Instant endedAt;
    @NotBlank @Pattern(regexp = "SUCCESS|FAILED|CANCELLED|UNKNOWN") private String status;
    private Boolean usageComplete;
    @NotNull @Size(max = 32) @Valid private List<Usage> usage;
}

package xiaozhi.modules.platform.dto;

import jakarta.validation.constraints.*;
import java.util.Map;

/** Secrets occur only in write-only inventory input and trusted execution responses. */
public final class PlatformRequests {
    private PlatformRequests() {}
    public record Grant(@NotBlank @Size(max=128) String requestId,@NotNull @Positive Long userId,@NotBlank String amount,@NotBlank @Size(max=500) String reason) {}
    public record PoolAdjustment(@NotBlank String requestId,@NotBlank String amount,@NotBlank @Size(max=500) String reason) {}
    public record Freeze(@NotBlank String requestId,@NotNull Long userId,boolean frozen,@NotBlank @Size(max=500) String reason) {}
    public record CredentialInput(@NotBlank String provider,@NotBlank @Size(max=200) String upstreamId,@NotEmpty @Size(max=12) Map<String,String> secrets) {
        @Override public String toString() { return "CredentialInput[redacted]"; }
    }
    public record Assignment(@NotBlank String requestId,@NotNull Long userId,@NotBlank String provider) {}
    public record Reason(@NotBlank @Size(max=500) String reason) {}
    public record Rate(@NotBlank @Size(max=64) String modelId,@NotBlank @Size(max=64) String version,@NotEmpty Map<String,String> metricRates,@NotEmpty Map<String,String> maxUsage) {}
    public record WorkspaceLimits(@NotBlank String workspaceId,String totalLimit,String dailyLimit,@Positive long expectedVersion) {}
    /** A user asking for credit. Amount is optional intent; the operator decides the real grant. */
    public record AllowanceRequest(@NotBlank String requestId,@Size(max=64) String amount,@NotBlank @Size(max=500) String reason,@Size(max=64) String workspaceId) {}
    public record AllowanceDecision(@NotBlank String requestId,@NotBlank String amount,@NotBlank @Size(max=500) String reason) {}
    public record Admit(@NotBlank String contextId,@NotBlank String contextToken,@NotBlank String modelId,@NotBlank String serviceType,@NotBlank String purpose,@NotBlank String logicalCallId,@NotBlank String attemptId,@NotEmpty Map<String,String> maxUsage) {}
    public record Permit(@NotBlank String reservationId,@NotBlank String permitToken) {}
    public record Settle(@NotBlank String reservationId,@NotBlank String permitToken,@NotBlank String status,boolean usageComplete,@NotNull Map<String,String> usage,@Size(max=255) String providerRequestId) {}
    public record Extend(@NotBlank String reservationId,@NotBlank String permitToken,@NotEmpty Map<String,String> maxUsage) {}
}

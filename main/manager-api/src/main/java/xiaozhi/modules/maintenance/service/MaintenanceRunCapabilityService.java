package xiaozhi.modules.maintenance.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import lombok.AllArgsConstructor;
import xiaozhi.common.constant.Constant;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.sys.service.SysParamsService;

/** Binds the internal Harness callback capability to one maintenance run. */
@Service
@AllArgsConstructor
public class MaintenanceRunCapabilityService {
    private static final String PREFIX = "maintenance-console-complete:";
    private final SysParamsService sysParamsService;

    public String issue(String runId) {
        return sign(requireRunId(runId));
    }

    public void verify(String runId, String supplied) {
        if (StringUtils.isBlank(supplied) || !MessageDigest.isEqual(
                sign(requireRunId(runId)).getBytes(StandardCharsets.US_ASCII),
                supplied.getBytes(StandardCharsets.US_ASCII))) {
            throw new RenException("原生 Harness 运行凭证无效");
        }
    }

    private String sign(String runId) {
        String serverSecret = sysParamsService.getValue(Constant.SERVER_SECRET, true);
        if (StringUtils.isBlank(serverSecret)) throw new RenException("服务器内部密钥未配置");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(serverSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mac.doFinal((PREFIX + runId).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RenException("无法生成原生 Harness 运行凭证");
        }
    }

    private String requireRunId(String runId) {
        if (StringUtils.isBlank(runId) || runId.length() > 64) throw new RenException("维护运行编号无效");
        return runId;
    }
}

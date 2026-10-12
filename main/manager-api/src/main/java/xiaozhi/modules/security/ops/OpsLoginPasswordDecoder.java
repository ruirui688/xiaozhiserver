package xiaozhi.modules.security.ops;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import xiaozhi.common.constant.Constant;
import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.utils.SM2Utils;
import xiaozhi.common.utils.Sm2DecryptUtil;
import xiaozhi.modules.security.service.CaptchaService;
import xiaozhi.modules.sys.service.SysParamsService;

/** The optional login CAPTCHA does not change enrollment or recovery decoding. */
@Component @RequiredArgsConstructor
public class OpsLoginPasswordDecoder {
    private final OpsLoginPolicy policy;
    private final CaptchaService captcha;
    private final SysParamsService params;

    public String decode(String encryptedPassword, String captchaId) {
        if (policy.captchaRequired()) {
            if (captchaId == null || captchaId.isBlank()) throw new RenException(ErrorCode.SMS_CAPTCHA_ERROR);
            return Sm2DecryptUtil.decryptAndValidateCaptcha(encryptedPassword, captchaId, captcha, params);
        }
        String key = params.getValue(Constant.SM2_PRIVATE_KEY, true);
        if (key == null || key.isBlank()) throw new RenException(ErrorCode.SM2_KEY_NOT_CONFIGURED);
        String password;
        try {
            password = SM2Utils.decrypt(key, encryptedPassword);
        } catch (Exception invalidCiphertext) {
            throw new RenException(ErrorCode.SM2_DECRYPT_ERROR);
        }
        if (password == null || password.isEmpty()) throw new RenException(ErrorCode.SM2_DECRYPT_ERROR);
        return password;
    }
}

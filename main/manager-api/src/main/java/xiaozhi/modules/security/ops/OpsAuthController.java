package xiaozhi.modules.security.ops;

import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import xiaozhi.common.utils.Result;
import xiaozhi.common.utils.Sm2DecryptUtil;
import xiaozhi.modules.security.dto.LoginDTO;
import xiaozhi.modules.security.service.CaptchaService;
import xiaozhi.modules.sys.service.SysParamsService;

@RestController @RequestMapping("/ops/auth") @RequiredArgsConstructor
public class OpsAuthController {
    private final OpsIdentityService identities;
    private final CaptchaService captcha;
    private final SysParamsService params;
    private final OpsEnrollmentService enrollment;
    @Data public static class Login extends LoginDTO { @NotBlank private String otpCode; }
    @Data public static class Enrollment extends Login { @NotBlank private String totpSecret; private String recoveryCode; }
    @PostMapping("/enroll")
    public Result<Map<String,Object>> enroll(@Valid @RequestBody Enrollment input,HttpServletRequest request) {
        String password=Sm2DecryptUtil.decryptAndValidateCaptcha(input.getPassword(),input.getCaptchaId(),captcha,params);
        return new Result<Map<String,Object>>().ok(enrollment.submit(input.getUsername(),password,input.getTotpSecret(),input.getOtpCode(),null,request.getRemoteAddr()));
    }
    @PostMapping("/recovery-request")
    public Result<Map<String,Object>> recovery(@Valid @RequestBody Enrollment input,HttpServletRequest request) {
        if(input.getRecoveryCode()==null||input.getRecoveryCode().isBlank()) throw new xiaozhi.common.exception.RenException("恢复码不能为空");
        String password=Sm2DecryptUtil.decryptAndValidateCaptcha(input.getPassword(),input.getCaptchaId(),captcha,params);
        return new Result<Map<String,Object>>().ok(enrollment.submit(input.getUsername(),password,input.getTotpSecret(),input.getOtpCode(),input.getRecoveryCode(),request.getRemoteAddr()));
    }
    @PostMapping("/recovery-code")
    public Result<Map<String,String>> recoveryCode(@Valid @RequestBody Login input) {
        String password=Sm2DecryptUtil.decryptAndValidateCaptcha(input.getPassword(),input.getCaptchaId(),captcha,params);
        return new Result<Map<String,String>>().ok(Map.of("recoveryCode",enrollment.issueRecoveryCode(password,input.getOtpCode())));
    }
    @PostMapping("/login")
    public Result<Map<String,Object>> login(@Valid @RequestBody Login login,HttpServletRequest request) {
        String password=Sm2DecryptUtil.decryptAndValidateCaptcha(login.getPassword(),login.getCaptchaId(),captcha,params);
        return new Result<Map<String,Object>>().ok(identities.login(login.getUsername(),password,login.getOtpCode(),request.getRemoteAddr()));
    }
    @GetMapping("/me")
    public Result<Map<String,Object>> me() {
        var user=OpsAccess.employee();
        return new Result<Map<String,Object>>().ok(Map.of("employeeId",user.getEmployeeId(),"username",user.getUsername(),"audience","OPS",
            "roles",user.getGrants().stream().map(OpsGrant::roleCode).distinct().toList(),"permissions",user.getGrants().stream().flatMap(g->g.permissions().stream()).distinct().sorted().toList(),"grants",user.getGrants()));
    }
    @PostMapping("/logout") public Result<Void> logout() { identities.logout(OpsAccess.employee().getToken()); return new Result<>(); }
}

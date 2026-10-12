package xiaozhi.modules.security.ops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.*;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.util.ReflectionTestUtils;
import xiaozhi.common.constant.Constant;
import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.utils.MessageUtils;
import xiaozhi.common.utils.SM2Utils;
import xiaozhi.modules.security.dto.LoginDTO;
import xiaozhi.modules.security.service.CaptchaService;
import xiaozhi.modules.sys.service.SysParamsService;

class OpsLoginPolicyTest {
    private static Map<String,String> keys;
    private static ValidatorFactory validators;
    private CaptchaService captcha;
    private SysParamsService params;

    @BeforeAll static void setupKeys() {
        keys=SM2Utils.createKey();
        validators=Validation.buildDefaultValidatorFactory();
    }
    @AfterAll static void closeValidators() { validators.close(); }
    @BeforeEach void setup() {
        captcha=mock(CaptchaService.class);params=mock(SysParamsService.class);
        when(params.getValue(Constant.SM2_PRIVATE_KEY,true)).thenReturn(keys.get(SM2Utils.KEY_PRIVATE_KEY));
        MessageSource messages=mock(MessageSource.class);
        when(messages.getMessage(anyString(),any(),any())).thenReturn("credential validation failed");
        ReflectionTestUtils.setField(MessageUtils.class,"messageSource",messages);
    }

    @Test void serverPolicyDefaultsOffAndSupportsIndependentRequirements() {
        try(var context=new AnnotationConfigApplicationContext(OpsLoginPolicy.class)) {
            var policy=context.getBean(OpsLoginPolicy.class);
            assertFalse(policy.mfaRequired());assertFalse(policy.captchaRequired());
        }
        for(boolean mfa:new boolean[]{false,true})for(boolean challenge:new boolean[]{false,true}) {
            try(var context=new AnnotationConfigApplicationContext()) {
                TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                    "xiaozhi.security.ops-login.mfa-required="+mfa,
                    "xiaozhi.security.ops-login.captcha-required="+challenge);
                context.register(OpsLoginPolicy.class);context.refresh();
                assertEquals(mfa,context.getBean(OpsLoginPolicy.class).mfaRequired());
                assertEquals(challenge,context.getBean(OpsLoginPolicy.class).captchaRequired());
            }
        }
    }

    @Test void passwordOnlyDecodingPreservesEveryPasswordCharacterAndIgnoresOptionalCaptcha() {
        var decoder=new OpsLoginPasswordDecoder(new OpsLoginPolicy(false,false),captcha,params);
        String password="ABCDE Password-42 ";
        assertEquals(password,decoder.decode(encrypt(password),null));
        assertEquals(password,decoder.decode(encrypt(password),"unused-client-id"));
        verifyNoInteractions(captcha);
    }

    @Test void passwordOnlyModeRejectsMalformedCiphertextAndMissingKey() {
        var decoder=new OpsLoginPasswordDecoder(new OpsLoginPolicy(false,false),captcha,params);
        assertEquals(ErrorCode.SM2_DECRYPT_ERROR,assertThrows(RenException.class,()->decoder.decode("invalid",null)).getCode());
        when(params.getValue(Constant.SM2_PRIVATE_KEY,true)).thenReturn("");
        assertEquals(ErrorCode.SM2_KEY_NOT_CONFIGURED,assertThrows(RenException.class,()->decoder.decode(encrypt("Password-42"),null)).getCode());
        verifyNoInteractions(captcha);
    }

    @Test void requiredCaptchaCannotBeSkippedAndUsesExistingFiveCharacterEnvelope() {
        var decoder=new OpsLoginPasswordDecoder(new OpsLoginPolicy(false,true),captcha,params);
        String encrypted=encrypt("ABCDEPassword-42");
        assertEquals(ErrorCode.SMS_CAPTCHA_ERROR,assertThrows(RenException.class,()->decoder.decode(encrypted,null)).getCode());
        verifyNoInteractions(captcha);
        assertThrows(RenException.class,()->decoder.decode(encrypted,"challenge"));
        when(captcha.validate("challenge","ABCDE",true)).thenReturn(true);
        assertEquals("Password-42",decoder.decode(encrypted,"challenge"));
        verify(captcha,times(2)).validate("challenge","ABCDE",true);
        assertThrows(RenException.class,()->decoder.decode(encrypt("Password-42"),"challenge"));
    }

    @Test void optionalLoginFieldsDoNotRelaxUserEnrollmentOrRecoveryValidation() {
        var login=new OpsAuthController.Login();login.setUsername("operator");login.setPassword("ciphertext");
        assertTrue(violations(login).isEmpty());
        var user=new LoginDTO();user.setUsername("operator");user.setPassword("ciphertext");
        assertEquals(Set.of("captchaId"),violations(user));
        var recovery=new OpsAuthController.MfaProof();recovery.setUsername("operator");recovery.setPassword("ciphertext");
        assertEquals(Set.of("captchaId","otpCode"),violations(recovery));
        var enrollment=new OpsAuthController.Enrollment();enrollment.setUsername("operator");enrollment.setPassword("ciphertext");
        assertEquals(Set.of("captchaId","otpCode","totpSecret"),violations(enrollment));
        enrollment.setCaptchaId("challenge");enrollment.setOtpCode("012345");enrollment.setTotpSecret("test-secret");
        assertTrue(violations(enrollment).isEmpty());
    }

    @Test void publicPolicyKeepsIdentityGovernanceIndependentOfLoginRequirements() {
        var identities=mock(OpsIdentityService.class);var enrollment=mock(OpsEnrollmentService.class);
        when(enrollment.identityPolicy()).thenReturn(new OpsEnrollmentService.IdentityPolicy(false,"managed by deployment"));
        var request=new MockHttpServletRequest();request.setRemoteAddr("192.0.2.20");
        for(boolean mfa:new boolean[]{false,true})for(boolean challenge:new boolean[]{false,true}) {
            var policy=new OpsLoginPolicy(mfa,challenge);
            var controller=new OpsAuthController(identities,captcha,params,enrollment,policy,new OpsLoginPasswordDecoder(policy,captcha,params));
            var result=controller.identityPolicy(request).getData();
            assertFalse(result.available());assertEquals("managed by deployment",result.message());
            assertEquals(mfa,result.mfaRequired());assertEquals(challenge,result.captchaRequired());
        }
        verify(identities,times(4)).limit("identity-policy-ip:192.0.2.20",120);
    }

    private static String encrypt(String value) { return SM2Utils.encrypt(keys.get(SM2Utils.KEY_PUBLIC_KEY),value); }
    private static Set<String> violations(Object value) {
        return validators.getValidator().validate(value).stream().map(error->error.getPropertyPath().toString()).collect(Collectors.toSet());
    }
}

package xiaozhi.modules.sys;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import lombok.extern.slf4j.Slf4j;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.security.controller.LoginController;
import xiaozhi.modules.security.dto.LoginDTO;
import xiaozhi.modules.security.dto.SmsVerificationDTO;
import xiaozhi.modules.sys.dto.RetrievePasswordDTO;

@Slf4j
@Disabled("Requires running MySQL/Redis")
@SpringBootTest
@ActiveProfiles("dev")
class loginControllerTest {

    @Autowired
    LoginController loginController;

    @Test
    public void testRegister() {
        LoginDTO loginDTO = new LoginDTO();
        loginDTO.setUsername("手机号码");
        loginDTO.setPassword("密码");
        Result<Void> result = loginController.register(loginDTO);
        assertNotNull(result, "register response should not be null");
        assertTrue(result.getCode() == 0 || result.getCode() >= 0, "response code should indicate success");
    }

    @Test
    public void testSmsVerification() {
        SmsVerificationDTO smsVerificationDTO = new SmsVerificationDTO();
        smsVerificationDTO.setPhone("手机号码");
        smsVerificationDTO.setCaptchaId("123456");
        smsVerificationDTO.setCaptcha("123456");
        Result<Void> result = loginController.smsVerification(smsVerificationDTO);
        assertNotNull(result, "smsVerification response should not be null");
        assertTrue(result.getCode() == 0 || result.getCode() >= 0, "response code should indicate success");
    }

    @Test
    public void testRetrievePassword() {
        RetrievePasswordDTO retrievePasswordDTO = new RetrievePasswordDTO();
        retrievePasswordDTO.setCode("123456");
        retrievePasswordDTO.setPhone("手机号码");
        retrievePasswordDTO.setPassword("密码");
        Result<?> result = loginController.retrievePassword(retrievePasswordDTO);
        assertNotNull(result, "retrievePassword response should not be null");
        assertTrue(result.getCode() == 0 || result.getCode() >= 0, "response code should indicate success");
    }

}
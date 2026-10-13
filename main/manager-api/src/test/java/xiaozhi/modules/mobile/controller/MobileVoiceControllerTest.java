package xiaozhi.modules.mobile.controller;

import org.junit.jupiter.api.Test;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.utils.MessageUtils;
import xiaozhi.common.user.UserDetail;
import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.modules.mobile.service.MobileVoiceSessionService;

class MobileVoiceControllerTest {
    final MobileVoiceSessionService sessions=mock(MobileVoiceSessionService.class);
    final MobileVoiceController controller=new MobileVoiceController(sessions);
    final MobileVoiceController.Request request=new MobileVoiceController.Request("agent-a","12345678-1234-4234-8234-123456789abc");
    @Test void rejectsOpsAudienceBeforeIssuingTicket() {
        UserDetail user=new UserDetail();user.setId(7L);user.setAudience("OPS");
        try(var security=mockStatic(SecurityUser.class);var messages=mockStatic(MessageUtils.class)){
            messages.when(()->MessageUtils.getMessage(ErrorCode.NO_PERMISSION)).thenReturn("denied");
            security.when(SecurityUser::getUser).thenReturn(user);
            assertThrows(RenException.class,()->controller.issue(request));verifyNoInteractions(sessions);
        }
    }
    @Test void passesAuthenticatedUserIdentityAndRequiresNormalPermission() throws Exception {
        UserDetail user=new UserDetail();user.setId(7L);user.setAudience("USER");
        try(var security=mockStatic(SecurityUser.class)){
            security.when(SecurityUser::getUser).thenReturn(user);
            security.when(SecurityUser::getUserId).thenReturn(7L);
            controller.issue(request);verify(sessions).issue(request.agentId(),request.installationId(),7L);
        }
        var permission=MobileVoiceController.class.getMethod("issue",MobileVoiceController.Request.class).getAnnotation(RequiresPermissions.class);
        assertArrayEquals(new String[]{"sys:role:normal"},permission.value());
    }
}

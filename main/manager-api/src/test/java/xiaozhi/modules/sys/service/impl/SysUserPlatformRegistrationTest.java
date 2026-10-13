package xiaozhi.modules.sys.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import xiaozhi.common.utils.SpringContextUtils;
import xiaozhi.modules.device.service.DeviceService;
import xiaozhi.modules.platform.service.PlatformAccountService;
import xiaozhi.modules.platform.service.PlatformTrialGrantService;
import xiaozhi.modules.sys.dao.SysUserDao;
import xiaozhi.modules.sys.dto.SysUserDTO;
import xiaozhi.modules.sys.entity.SysUserEntity;
import xiaozhi.modules.sys.service.SysParamsService;
import xiaozhi.modules.workspace.service.WorkspaceService;

class SysUserPlatformRegistrationTest {
    @Test void passwordAndWechatRegistrationBothCreateAccountAndWorkspaceBeforeTrial() {
        var dao=mock(SysUserDao.class);var workspace=mock(WorkspaceService.class);
        var accounts=mock(PlatformAccountService.class);var trial=mock(PlatformTrialGrantService.class);
        var service=spy(new SysUserServiceImpl(dao,mock(DeviceService.class),mock(SysParamsService.class),workspace));
        doAnswer(call->{((SysUserEntity)call.getArgument(0)).setId(42L);return true;}).when(service).insert(any(SysUserEntity.class));
        when(dao.selectList(any())).thenReturn(List.of());
        try(var context=mockStatic(SpringContextUtils.class)) {
            context.when(()->SpringContextUtils.getBean(PlatformAccountService.class)).thenReturn(accounts);
            context.when(()->SpringContextUtils.getBean(PlatformTrialGrantService.class)).thenReturn(trial);
            SysUserDTO input=new SysUserDTO();input.setUsername("test-user");input.setPassword("Test-only-Strong-42!");
            service.save(input);
            var order=inOrder(accounts,workspace,trial);order.verify(accounts).ensureUser(42);order.verify(workspace).ensureDefaultWorkspace(42L);order.verify(trial).issue(42);
            clearInvocations(accounts,workspace,trial);
            assertEquals(42L,service.createWechatUser("test-openid","test-unionid").getId());
            order=inOrder(accounts,workspace,trial);order.verify(accounts).ensureUser(42);order.verify(workspace).ensureDefaultWorkspace(42L);order.verify(trial).issue(42);
            // The provisioning hook never requests supplier credentials or an extra platform API key.
            context.verify(()->SpringContextUtils.getBean(PlatformAccountService.class),times(2));
            context.verify(()->SpringContextUtils.getBean(PlatformTrialGrantService.class),times(2));context.verifyNoMoreInteractions();
        }
    }
}

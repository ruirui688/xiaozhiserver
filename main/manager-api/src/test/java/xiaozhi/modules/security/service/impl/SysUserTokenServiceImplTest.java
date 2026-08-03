package xiaozhi.modules.security.service.impl;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import xiaozhi.modules.security.dao.SysUserTokenDao;
import xiaozhi.modules.security.entity.SysUserTokenEntity;
import xiaozhi.modules.sys.service.SysUserService;

class SysUserTokenServiceImplTest {
    @Test
    void createsIndependentTokensForConcurrentSessions() {
        SysUserTokenDao tokenDao = mock(SysUserTokenDao.class);
        SysUserTokenServiceImpl service = new SysUserTokenServiceImpl(mock(SysUserService.class));
        ReflectionTestUtils.setField(service, "baseDao", tokenDao);

        service.createToken(1001L);
        service.createToken(1001L);

        ArgumentCaptor<SysUserTokenEntity> captor = ArgumentCaptor.forClass(SysUserTokenEntity.class);
        verify(tokenDao, times(2)).insert(captor.capture());
        List<SysUserTokenEntity> sessions = captor.getAllValues();
        assertNotEquals(sessions.get(0).getToken(), sessions.get(1).getToken());
        verify(tokenDao, times(2)).deleteExpiredByUserId(any(), any());
    }

    @Test
    void logoutExpiresOnlyCurrentSessionAndPasswordChangeExpiresAll() {
        SysUserTokenDao tokenDao = mock(SysUserTokenDao.class);
        SysUserService userService = mock(SysUserService.class);
        SysUserTokenServiceImpl service = new SysUserTokenServiceImpl(userService);
        ReflectionTestUtils.setField(service, "baseDao", tokenDao);

        service.logout("session-token");
        service.logoutAll(1001L);

        verify(tokenDao).expireByToken(org.mockito.ArgumentMatchers.eq("session-token"), any());
        verify(tokenDao).expireByUserId(org.mockito.ArgumentMatchers.eq(1001L), any());
    }
}

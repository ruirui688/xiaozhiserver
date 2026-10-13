package xiaozhi.modules.sys.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import xiaozhi.modules.device.service.DeviceService;
import xiaozhi.modules.sys.dao.SysUserDao;
import xiaozhi.modules.sys.entity.SysUserEntity;
import xiaozhi.modules.sys.service.SysParamsService;
import xiaozhi.modules.workspace.service.WorkspaceService;

class SysUserStatusChangeTest {

    @Test
    void disablingUsersPreparesSortedDistinctIdsBeforeUpdatingInTheSameOrder() {
        SysUserDao userDao = mock(SysUserDao.class);
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        SysUserServiceImpl service = service(userDao, workspaceService);
        when(userDao.updateById(any(SysUserEntity.class))).thenReturn(1);

        service.changeStatus(0, new String[] { "30", "20", "10", "20", "30" });

        var order = inOrder(workspaceService, userDao);
        ArgumentCaptor<List<Long>> preparedUserIds = longListCaptor();
        order.verify(workspaceService).prepareUsersDisabled(preparedUserIds.capture());
        assertEquals(List.of(10L, 20L, 30L), preparedUserIds.getValue());
        ArgumentCaptor<SysUserEntity> updates = ArgumentCaptor.forClass(SysUserEntity.class);
        order.verify(userDao, times(3)).updateById(updates.capture());
        assertEquals(List.of(10L, 20L, 30L), updates.getAllValues().stream()
                .map(SysUserEntity::getId)
                .toList());
        assertEquals(List.of(0, 0, 0), updates.getAllValues().stream()
                .map(SysUserEntity::getStatus)
                .toList());
        verifyNoMoreInteractions(workspaceService);
    }

    @Test
    void enablingUsersDoesNotRunWorkspaceDisablePreparation() {
        SysUserDao userDao = mock(SysUserDao.class);
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        SysUserServiceImpl service = service(userDao, workspaceService);
        when(userDao.updateById(any(SysUserEntity.class))).thenReturn(1);

        service.changeStatus(1, new String[] { "9", "7" });

        verifyNoInteractions(workspaceService);
        ArgumentCaptor<SysUserEntity> updates = ArgumentCaptor.forClass(SysUserEntity.class);
        verify(userDao, times(2)).updateById(updates.capture());
        assertEquals(List.of(1, 1), updates.getAllValues().stream()
                .map(SysUserEntity::getStatus)
                .toList());
    }

    @Test
    void disablingThenReEnablingOnlyPreparesWorkspaceDuringDisable() {
        SysUserDao userDao = mock(SysUserDao.class);
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        SysUserServiceImpl service = service(userDao, workspaceService);
        when(userDao.updateById(any(SysUserEntity.class))).thenReturn(1);

        service.changeStatus(0, new String[] { "20", "10" });

        ArgumentCaptor<List<Long>> preparedUserIds = longListCaptor();
        verify(workspaceService, times(1)).prepareUsersDisabled(preparedUserIds.capture());
        assertEquals(List.of(10L, 20L), preparedUserIds.getValue());

        service.changeStatus(1, new String[] { "20", "10" });

        verifyNoMoreInteractions(workspaceService);

        ArgumentCaptor<SysUserEntity> updates = ArgumentCaptor.forClass(SysUserEntity.class);
        verify(userDao, times(4)).updateById(updates.capture());
        assertEquals(List.of(10L, 20L, 10L, 20L), updates.getAllValues().stream()
                .map(SysUserEntity::getId)
                .toList());
        assertEquals(List.of(0, 0, 1, 1), updates.getAllValues().stream()
                .map(SysUserEntity::getStatus)
                .toList());
    }

    private SysUserServiceImpl service(SysUserDao userDao, WorkspaceService workspaceService) {
        SysUserServiceImpl service = new SysUserServiceImpl(
                userDao,
                mock(DeviceService.class),
                mock(SysParamsService.class),
                workspaceService);
        ReflectionTestUtils.setField(service, "baseDao", userDao);
        return service;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private ArgumentCaptor<List<Long>> longListCaptor() {
        return ArgumentCaptor.forClass((Class) List.class);
    }
}

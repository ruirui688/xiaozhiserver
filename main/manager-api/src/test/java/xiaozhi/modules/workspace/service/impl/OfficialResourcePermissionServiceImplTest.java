package xiaozhi.modules.workspace.service.impl;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.modules.sys.dao.SysUserDao;
import xiaozhi.modules.workspace.dao.WorkspaceDao;
import xiaozhi.modules.workspace.dao.WorkspaceMemberDao;
import xiaozhi.modules.workspace.entity.WorkspaceEntity;
import xiaozhi.modules.workspace.entity.WorkspaceMemberEntity;

class OfficialResourcePermissionServiceImplTest {
    @Test
    void memberOfSuperAdminOwnedWorkspaceIsOfficialMaintainer() {
        WorkspaceMemberDao memberDao = mock(WorkspaceMemberDao.class);
        WorkspaceDao workspaceDao = mock(WorkspaceDao.class);
        SysUserDao userDao = mock(SysUserDao.class);
        WorkspaceMemberEntity member = new WorkspaceMemberEntity();
        member.setWorkspaceId("official-team");
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId("official-team");
        workspace.setOwnerUserId(1L);
        when(memberDao.selectList(any())).thenReturn(List.of(member));
        when(workspaceDao.selectList(any())).thenReturn(List.of(workspace));
        when(userDao.selectCount(any())).thenReturn(0L, 1L);

        OfficialResourcePermissionServiceImpl service =
                new OfficialResourcePermissionServiceImpl(memberDao, workspaceDao, userDao);
        try (MockedStatic<SecurityUser> security = mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUserId).thenReturn(7L);
            assertTrue(service.isOfficialMaintainer());
        }
    }
}

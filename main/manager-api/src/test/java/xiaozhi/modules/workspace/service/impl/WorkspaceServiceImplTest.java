package xiaozhi.modules.workspace.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.context.MessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.baomidou.mybatisplus.core.conditions.Wrapper;

import xiaozhi.common.exception.RenException;
import xiaozhi.common.user.UserDetail;
import xiaozhi.common.utils.MessageUtils;
import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.modules.sys.dao.SysUserDao;
import xiaozhi.modules.sys.entity.SysUserEntity;
import xiaozhi.modules.sys.enums.SuperAdminEnum;
import xiaozhi.modules.workspace.dao.WorkspaceDao;
import xiaozhi.modules.workspace.dao.WorkspaceInvitationDao;
import xiaozhi.modules.workspace.dao.WorkspaceMemberDao;
import xiaozhi.modules.workspace.dto.WorkspaceCreateDTO;
import xiaozhi.modules.workspace.dto.WorkspaceInviteDTO;
import xiaozhi.modules.workspace.dto.WorkspaceRoleUpdateDTO;
import xiaozhi.modules.workspace.entity.WorkspaceEntity;
import xiaozhi.modules.workspace.entity.WorkspaceMemberEntity;
import xiaozhi.modules.workspace.service.WorkspaceService;

class WorkspaceServiceImplTest {

    private static final Long USER_ID = 1001L;
    private final WorkspaceDao workspaceDao = mock(WorkspaceDao.class);
    private final WorkspaceMemberDao memberDao = mock(WorkspaceMemberDao.class);
    private final WorkspaceInvitationDao invitationDao = mock(WorkspaceInvitationDao.class);
    private final SysUserDao sysUserDao = mock(SysUserDao.class);
    private final WorkspaceServiceImpl workspaceService =
            new WorkspaceServiceImpl(workspaceDao, memberDao, invitationDao, sysUserDao);

    @BeforeEach
    void setUp() {
        MessageSource messageSource = mock(MessageSource.class);
        when(messageSource.getMessage(any(String.class), any(Object[].class), any())).thenReturn("no permission");
        ReflectionTestUtils.setField(MessageUtils.class, "messageSource", messageSource);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void requestedWorkspaceHeaderCannotSelectWorkspaceWhereUserIsNotMember() {
        WorkspaceEntity workspace = workspace("workspace-b", 2002L);
        when(workspaceDao.selectById("workspace-b")).thenReturn(workspace);
        when(memberDao.selectOne(any(Wrapper.class))).thenReturn(null);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Workspace-Id", "workspace-b");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        try (MockedStatic<SecurityUser> ignored = mockSecurityUser(USER_ID, false)) {
            assertThrows(RenException.class, workspaceService::getCurrentWorkspaceEntity);
        }
    }

    @Test
    void memberCanReadButCannotWriteWorkspace() {
        when(memberDao.selectOne(any(Wrapper.class)))
                .thenReturn(member("workspace-a", USER_ID, WorkspaceService.ROLE_MEMBER));

        try (MockedStatic<SecurityUser> ignored = mockSecurityUser(USER_ID, false)) {
            assertTrue(workspaceService.canRead("workspace-a", USER_ID));
            assertEquals(false, workspaceService.canWrite("workspace-a", USER_ID));
            assertThrows(RenException.class, () -> workspaceService.requireWrite("workspace-a"));
        }
    }

    @Test
    void ensureDefaultWorkspaceCreatesOwnerWorkspaceAndMembershipForNewUser() {
        SysUserEntity user = new SysUserEntity();
        user.setId(USER_ID);
        user.setUsername("alice");
        when(workspaceDao.selectOne(any(Wrapper.class))).thenReturn(null);
        when(sysUserDao.selectById(USER_ID)).thenReturn(user);

        WorkspaceEntity workspace = workspaceService.ensureDefaultWorkspace(USER_ID);

        ArgumentCaptor<WorkspaceEntity> workspaceCaptor = ArgumentCaptor.forClass(WorkspaceEntity.class);
        ArgumentCaptor<WorkspaceMemberEntity> memberCaptor = ArgumentCaptor.forClass(WorkspaceMemberEntity.class);
        org.mockito.Mockito.verify(workspaceDao).insert(workspaceCaptor.capture());
        org.mockito.Mockito.verify(memberDao).insert(memberCaptor.capture());

        assertEquals(workspace.getId(), workspaceCaptor.getValue().getId());
        assertEquals("alice的工作空间", workspaceCaptor.getValue().getName());
        assertEquals(USER_ID, workspaceCaptor.getValue().getOwnerUserId());
        assertEquals(1, workspaceCaptor.getValue().getDefaultWorkspace());
        assertEquals(workspace.getId(), memberCaptor.getValue().getWorkspaceId());
        assertEquals(USER_ID, memberCaptor.getValue().getUserId());
        assertEquals(WorkspaceService.ROLE_OWNER, memberCaptor.getValue().getRole());
    }

    @Test
    void adminCannotRemoveOwnerMember() {
        when(memberDao.selectOne(any(Wrapper.class))).thenReturn(
                member("workspace-a", USER_ID, WorkspaceService.ROLE_ADMIN),
                member("workspace-a", 2002L, WorkspaceService.ROLE_OWNER),
                member("workspace-a", USER_ID, WorkspaceService.ROLE_ADMIN));

        try (MockedStatic<SecurityUser> ignored = mockSecurityUser(USER_ID, false)) {
            assertThrows(RenException.class, () -> workspaceService.removeMember("workspace-a", 2002L));
        }
    }

    @Test
    void ownerCanRemoveAnotherOwnerWhenWorkspaceStillHasOwner() {
        when(memberDao.selectOne(any(Wrapper.class))).thenReturn(
                member("workspace-a", USER_ID, WorkspaceService.ROLE_OWNER),
                member("workspace-a", 2002L, WorkspaceService.ROLE_OWNER),
                member("workspace-a", USER_ID, WorkspaceService.ROLE_OWNER));
        when(memberDao.selectCount(any(Wrapper.class))).thenReturn(2L);

        try (MockedStatic<SecurityUser> ignored = mockSecurityUser(USER_ID, false)) {
            workspaceService.removeMember("workspace-a", 2002L);
        }

        verify(memberDao).update(any(), any());
    }

    @Test
    void removeMemberRejectsRemovingSelf() {
        when(memberDao.selectOne(any(Wrapper.class)))
                .thenReturn(member("workspace-a", USER_ID, WorkspaceService.ROLE_OWNER));

        try (MockedStatic<SecurityUser> ignored = mockSecurityUser(USER_ID, false)) {
            assertThrows(RenException.class, () -> workspaceService.removeMember("workspace-a", USER_ID));
        }
    }

    @Test
    void adminCannotGrantOwnerRole() {
        WorkspaceRoleUpdateDTO dto = new WorkspaceRoleUpdateDTO();
        dto.setRole(WorkspaceService.ROLE_OWNER);
        when(memberDao.selectOne(any(Wrapper.class))).thenReturn(
                member("workspace-a", USER_ID, WorkspaceService.ROLE_ADMIN),
                member("workspace-a", 2002L, WorkspaceService.ROLE_MEMBER),
                member("workspace-a", USER_ID, WorkspaceService.ROLE_ADMIN));

        try (MockedStatic<SecurityUser> ignored = mockSecurityUser(USER_ID, false)) {
            assertThrows(RenException.class, () -> workspaceService.updateMemberRole("workspace-a", 2002L, dto));
        }
    }

    @Test
    void createWorkspaceIsRejectedBecauseUserHasSingleDefaultWorkspace() {
        WorkspaceCreateDTO dto = new WorkspaceCreateDTO();
        dto.setName("new workspace");

        assertThrows(RenException.class, () -> workspaceService.createWorkspace(dto));
    }

    @Test
    void adminCannotUpdateMemberRoleEvenForNonOwnerMember() {
        WorkspaceRoleUpdateDTO dto = new WorkspaceRoleUpdateDTO();
        dto.setRole(WorkspaceService.ROLE_ADMIN);
        when(memberDao.selectOne(any(Wrapper.class)))
                .thenReturn(member("workspace-a", USER_ID, WorkspaceService.ROLE_ADMIN));

        try (MockedStatic<SecurityUser> ignored = mockSecurityUser(USER_ID, false)) {
            assertThrows(RenException.class, () -> workspaceService.updateMemberRole("workspace-a", 2002L, dto));
        }
    }

    @Test
    void ownerCannotUpdateOwnRole() {
        WorkspaceRoleUpdateDTO dto = new WorkspaceRoleUpdateDTO();
        dto.setRole(WorkspaceService.ROLE_MEMBER);
        when(memberDao.selectOne(any(Wrapper.class)))
                .thenReturn(member("workspace-a", USER_ID, WorkspaceService.ROLE_OWNER));

        try (MockedStatic<SecurityUser> ignored = mockSecurityUser(USER_ID, false)) {
            assertThrows(RenException.class, () -> workspaceService.updateMemberRole("workspace-a", USER_ID, dto));
        }
    }

    @Test
    void inviteRejectsWhenActiveAndPendingMembersReachLimit() {
        WorkspaceInviteDTO dto = new WorkspaceInviteDTO();
        dto.setUsername("bob");
        dto.setRole(WorkspaceService.ROLE_MEMBER);
        when(memberDao.selectOne(any(Wrapper.class)))
                .thenReturn(member("workspace-a", USER_ID, WorkspaceService.ROLE_OWNER));
        when(memberDao.selectCount(any(Wrapper.class))).thenReturn(4L);
        when(invitationDao.selectCount(any(Wrapper.class))).thenReturn(1L);

        try (MockedStatic<SecurityUser> ignored = mockSecurityUser(USER_ID, false)) {
            assertThrows(RenException.class, () -> workspaceService.invite("workspace-a", dto));
        }
    }

    @Test
    void ownerCannotLeaveWorkspaceDirectly() {
        when(memberDao.selectOne(any(Wrapper.class)))
                .thenReturn(member("workspace-a", USER_ID, WorkspaceService.ROLE_OWNER));

        try (MockedStatic<SecurityUser> ignored = mockSecurityUser(USER_ID, false)) {
            assertThrows(RenException.class, () -> workspaceService.leaveWorkspace("workspace-a"));
        }
    }

    @Test
    void prepareUserDeletionRejectsLastOwnerMembership() {
        when(memberDao.selectList(any(Wrapper.class)))
                .thenReturn(List.of(member("workspace-a", USER_ID, WorkspaceService.ROLE_OWNER)));
        when(memberDao.selectCount(any(Wrapper.class))).thenReturn(1L);

        assertThrows(RenException.class, () -> workspaceService.prepareUserDeletion(USER_ID));
    }

    @Test
    void prepareUserDeletionDisablesMembershipsWhenUserIsNotLastOwner() {
        when(memberDao.selectList(any(Wrapper.class))).thenReturn(List.of(
                member("workspace-a", USER_ID, WorkspaceService.ROLE_OWNER),
                member("workspace-b", USER_ID, WorkspaceService.ROLE_MEMBER)));
        when(memberDao.selectCount(any(Wrapper.class))).thenReturn(2L);

        workspaceService.prepareUserDeletion(USER_ID);

        verify(memberDao).update(any(), any());
    }

    private MockedStatic<SecurityUser> mockSecurityUser(Long userId, boolean superAdmin) {
        UserDetail user = new UserDetail();
        user.setId(userId);
        user.setSuperAdmin(superAdmin ? SuperAdminEnum.YES.value() : SuperAdminEnum.NO.value());
        MockedStatic<SecurityUser> securityUser = mockStatic(SecurityUser.class);
        securityUser.when(SecurityUser::getUser).thenReturn(user);
        securityUser.when(SecurityUser::getUserId).thenReturn(userId);
        return securityUser;
    }

    private WorkspaceEntity workspace(String workspaceId, Long ownerId) {
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(workspaceId);
        workspace.setName(workspaceId);
        workspace.setOwnerUserId(ownerId);
        workspace.setStatus(1);
        workspace.setDefaultWorkspace(0);
        return workspace;
    }

    private WorkspaceMemberEntity member(String workspaceId, Long userId, String role) {
        WorkspaceMemberEntity member = new WorkspaceMemberEntity();
        member.setId(workspaceId + "-" + userId);
        member.setWorkspaceId(workspaceId);
        member.setUserId(userId);
        member.setRole(role);
        member.setStatus(1);
        return member;
    }

}

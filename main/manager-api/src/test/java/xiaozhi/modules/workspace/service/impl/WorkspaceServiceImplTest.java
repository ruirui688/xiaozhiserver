package xiaozhi.modules.workspace.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.context.MessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;

import xiaozhi.common.exception.RenException;
import xiaozhi.common.user.UserDetail;
import xiaozhi.common.utils.MessageUtils;
import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.modules.sys.dao.SysUserDao;
import xiaozhi.modules.sys.entity.SysUserEntity;

import xiaozhi.modules.workspace.dao.WorkspaceDao;
import xiaozhi.modules.workspace.dao.WorkspaceInvitationDao;
import xiaozhi.modules.workspace.dao.WorkspaceMemberDao;
import xiaozhi.modules.workspace.dto.WorkspaceCreateDTO;
import xiaozhi.modules.workspace.dto.WorkspaceInviteDTO;
import xiaozhi.modules.workspace.dto.WorkspaceRoleUpdateDTO;
import xiaozhi.modules.workspace.entity.WorkspaceEntity;
import xiaozhi.modules.workspace.entity.WorkspaceInvitationEntity;
import xiaozhi.modules.workspace.entity.WorkspaceMemberEntity;
import xiaozhi.modules.workspace.service.WorkspaceService;
import xiaozhi.modules.workspace.vo.WorkspaceVO;

class WorkspaceServiceImplTest {

    private static final Long USER_ID = 1001L;
    private final WorkspaceDao workspaceDao = mock(WorkspaceDao.class);
    private final WorkspaceMemberDao memberDao = mock(WorkspaceMemberDao.class);
    private final WorkspaceInvitationDao invitationDao = mock(WorkspaceInvitationDao.class);
    private final SysUserDao sysUserDao = mock(SysUserDao.class);
    private final WorkspaceServiceImpl workspaceService =
            new WorkspaceServiceImpl(workspaceDao, memberDao, invitationDao, sysUserDao);

    private MockedStatic<xiaozhi.common.utils.SpringContextUtils> context;
    @BeforeEach
    void setUp() {
        context = mockStatic(xiaozhi.common.utils.SpringContextUtils.class);
        context.when(() -> xiaozhi.common.utils.SpringContextUtils.getBean(xiaozhi.modules.platform.service.PlatformResponsibilityService.class)).thenReturn(mock(xiaozhi.modules.platform.service.PlatformResponsibilityService.class));
        MessageSource messageSource = mock(MessageSource.class);
        when(messageSource.getMessage(any(String.class), any(Object[].class), any())).thenReturn("no permission");
        ReflectionTestUtils.setField(MessageUtils.class, "messageSource", messageSource);
        when(sysUserDao.selectByIdForUpdate(USER_ID)).thenReturn(user(USER_ID, "alice"));
        when(sysUserDao.selectByIdForUpdate(2002L)).thenReturn(user(2002L, "creator"));
        when(sysUserDao.selectById(2002L)).thenReturn(user(2002L, "creator"));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        context.close();
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
        when(sysUserDao.selectByIdForUpdate(USER_ID)).thenReturn(user);

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
        var lockOrder = inOrder(sysUserDao, workspaceDao, memberDao);
        lockOrder.verify(sysUserDao).selectByIdForUpdate(USER_ID);
        lockOrder.verify(workspaceDao).insert(any(WorkspaceEntity.class));
        lockOrder.verify(memberDao).insert(any(WorkspaceMemberEntity.class));
    }

    @Test
    void adminCannotRemoveOwnerMember() {
        when(memberDao.selectOne(any(Wrapper.class)))
                .thenReturn(member("workspace-a", USER_ID, WorkspaceService.ROLE_ADMIN));
        when(workspaceDao.lockByIdForInvitation("workspace-a"))
                .thenReturn(workspace("workspace-a", 3003L));
        when(memberDao.selectByWorkspaceAndUserForUpdate("workspace-a", USER_ID))
                .thenReturn(member("workspace-a", USER_ID, WorkspaceService.ROLE_ADMIN));
        when(memberDao.selectByWorkspaceAndUserForUpdate("workspace-a", 2002L))
                .thenReturn(member("workspace-a", 2002L, WorkspaceService.ROLE_OWNER));

        try (MockedStatic<SecurityUser> ignored = mockSecurityUser(USER_ID, false)) {
            assertThrows(RenException.class, () -> workspaceService.removeMember("workspace-a", 2002L));
        }
    }

    @Test
    void ownerCanRemoveAnotherOwnerWhenWorkspaceStillHasOwner() {
        WorkspaceMemberEntity currentOwner = member("workspace-a", USER_ID, WorkspaceService.ROLE_OWNER);
        WorkspaceMemberEntity removedOwner = member("workspace-a", 2002L, WorkspaceService.ROLE_OWNER);
        when(memberDao.selectOne(any(Wrapper.class))).thenReturn(currentOwner);
        when(workspaceDao.lockByIdForInvitation("workspace-a"))
                .thenReturn(workspace("workspace-a", 3003L));
        when(memberDao.selectByWorkspaceAndUserForUpdate("workspace-a", USER_ID))
                .thenReturn(currentOwner);
        when(memberDao.selectByWorkspaceAndUserForUpdate("workspace-a", 2002L))
                .thenReturn(removedOwner);
        when(memberDao.selectActiveByWorkspaceForUpdate("workspace-a"))
                .thenReturn(List.of(currentOwner, removedOwner));

        try (MockedStatic<SecurityUser> ignored = mockSecurityUser(USER_ID, false)) {
            workspaceService.removeMember("workspace-a", 2002L);
        }

        verify(memberDao).update(any(), any());
        var lockOrder = inOrder(workspaceDao, memberDao);
        lockOrder.verify(workspaceDao).lockByIdForInvitation("workspace-a");
        lockOrder.verify(memberDao).update(any(), any());
        verify(invitationDao).update(isNull(), any(UpdateWrapper.class));
    }

    @Test
    void removeMemberRejectsRemovingSelf() {
        when(memberDao.selectOne(any(Wrapper.class)))
                .thenReturn(member("workspace-a", USER_ID, WorkspaceService.ROLE_OWNER));
        when(workspaceDao.lockByIdForInvitation("workspace-a"))
                .thenReturn(workspace("workspace-a", 2002L));
        when(memberDao.selectByWorkspaceAndUserForUpdate("workspace-a", USER_ID))
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
    void ownerCannotGrantOwnerRoleThroughMemberRoleApi() {
        WorkspaceRoleUpdateDTO dto = new WorkspaceRoleUpdateDTO();
        dto.setRole(WorkspaceService.ROLE_OWNER);
        WorkspaceMemberEntity currentOwner = member("workspace-a", USER_ID, WorkspaceService.ROLE_OWNER);
        WorkspaceMemberEntity targetMember = member("workspace-a", 2002L, WorkspaceService.ROLE_MEMBER);
        when(memberDao.selectOne(any(Wrapper.class))).thenReturn(currentOwner);
        when(workspaceDao.lockByIdForInvitation("workspace-a"))
                .thenReturn(workspace("workspace-a", 3003L));
        when(memberDao.selectByWorkspaceAndUserForUpdate("workspace-a", USER_ID))
                .thenReturn(currentOwner);
        when(memberDao.selectByWorkspaceAndUserForUpdate("workspace-a", 2002L))
                .thenReturn(targetMember);

        try (MockedStatic<SecurityUser> ignored = mockSecurityUser(USER_ID, false)) {
            assertThrows(RenException.class,
                    () -> workspaceService.updateMemberRole("workspace-a", 2002L, dto));
        }

        assertEquals(WorkspaceService.ROLE_MEMBER, targetMember.getRole());
        verify(memberDao, never()).updateById(any(WorkspaceMemberEntity.class));
    }

    @Test
    void workspaceReadEntryPointsAreTransactionalForDefaultWorkspaceLocking() throws Exception {
        assertNotNull(WorkspaceServiceImpl.class.getMethod("listCurrentUserWorkspaces")
                .getAnnotation(Transactional.class));
        assertNotNull(WorkspaceServiceImpl.class.getMethod("getCurrentWorkspace")
                .getAnnotation(Transactional.class));
        assertNotNull(WorkspaceServiceImpl.class.getMethod("getCurrentWorkspaceEntity")
                .getAnnotation(Transactional.class));
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
        when(workspaceDao.lockByIdForInvitation("workspace-a"))
                .thenReturn(workspace("workspace-a", 2002L));
        when(memberDao.selectByWorkspaceAndUserForUpdate("workspace-a", USER_ID))
                .thenReturn(member("workspace-a", USER_ID, WorkspaceService.ROLE_OWNER));

        try (MockedStatic<SecurityUser> ignored = mockSecurityUser(USER_ID, false)) {
            assertThrows(RenException.class, () -> workspaceService.updateMemberRole("workspace-a", USER_ID, dto));
        }
    }

    @Test
    void ownerRoleChangeLocksWorkspaceBeforeUpdatingMembership() {
        WorkspaceRoleUpdateDTO dto = new WorkspaceRoleUpdateDTO();
        dto.setRole(WorkspaceService.ROLE_ADMIN);
        WorkspaceMemberEntity currentOwner = member("workspace-a", USER_ID, WorkspaceService.ROLE_OWNER);
        WorkspaceMemberEntity targetMember = member("workspace-a", 2002L, WorkspaceService.ROLE_MEMBER);
        when(memberDao.selectOne(any(Wrapper.class))).thenReturn(currentOwner);
        when(workspaceDao.lockByIdForInvitation("workspace-a"))
                .thenReturn(workspace("workspace-a", 3003L));
        when(memberDao.selectByWorkspaceAndUserForUpdate("workspace-a", USER_ID))
                .thenReturn(currentOwner);
        when(memberDao.selectByWorkspaceAndUserForUpdate("workspace-a", 2002L))
                .thenReturn(targetMember);

        try (MockedStatic<SecurityUser> ignored = mockSecurityUser(USER_ID, false)) {
            workspaceService.updateMemberRole("workspace-a", 2002L, dto);
        }

        var lockOrder = inOrder(workspaceDao, memberDao);
        lockOrder.verify(workspaceDao).lockByIdForInvitation("workspace-a");
        lockOrder.verify(memberDao).updateById(any(WorkspaceMemberEntity.class));
    }

    @Test
    void downgradingInvitationCreatorPermanentlyRevokesPendingInvitations() {
        WorkspaceRoleUpdateDTO dto = new WorkspaceRoleUpdateDTO();
        dto.setRole(WorkspaceService.ROLE_MEMBER);
        WorkspaceMemberEntity currentOwner = member("workspace-a", USER_ID, WorkspaceService.ROLE_OWNER);
        WorkspaceMemberEntity downgradedAdmin = member("workspace-a", 2002L, WorkspaceService.ROLE_ADMIN);
        when(memberDao.selectOne(any(Wrapper.class))).thenReturn(currentOwner);
        when(workspaceDao.lockByIdForInvitation("workspace-a"))
                .thenReturn(workspace("workspace-a", 3003L));
        when(memberDao.selectByWorkspaceAndUserForUpdate("workspace-a", USER_ID))
                .thenReturn(currentOwner);
        when(memberDao.selectByWorkspaceAndUserForUpdate("workspace-a", 2002L))
                .thenReturn(downgradedAdmin);

        try (MockedStatic<SecurityUser> ignored = mockSecurityUser(USER_ID, false)) {
            workspaceService.updateMemberRole("workspace-a", 2002L, dto);
        }

        assertEquals(WorkspaceService.ROLE_MEMBER, downgradedAdmin.getRole());
        ArgumentCaptor<UpdateWrapper<WorkspaceInvitationEntity>> revokeCaptor = updateWrapperCaptor();
        verify(invitationDao).update(isNull(), revokeCaptor.capture());
        UpdateWrapper<WorkspaceInvitationEntity> revoke = revokeCaptor.getValue();
        assertTrue(revoke.getSqlSet().contains("status"));
        String revokeConditions = revoke.getCustomSqlSegment();
        assertTrue(revokeConditions.contains("workspace_id"));
        assertTrue(revokeConditions.contains("creator"));
        assertTrue(revokeConditions.contains("status"));
        assertTrue(revoke.getParamNameValuePairs().containsValue("workspace-a"));
        assertTrue(revoke.getParamNameValuePairs().containsValue(2002L));
        assertTrue(revoke.getParamNameValuePairs().containsValue("PENDING"));
        assertTrue(revoke.getParamNameValuePairs().containsValue("REVOKED"));
    }

    @Test
    void inviteRejectsWhenActiveAndPendingMembersReachLimit() {
        WorkspaceMemberEntity currentOwner = member("workspace-a", USER_ID, WorkspaceService.ROLE_OWNER);
        when(workspaceDao.lockByIdForInvitation("workspace-a"))
                .thenReturn(workspace("workspace-a", USER_ID));
        WorkspaceInviteDTO dto = new WorkspaceInviteDTO();
        dto.setUsername("bob");
        dto.setRole(WorkspaceService.ROLE_MEMBER);
        when(memberDao.selectOne(any(Wrapper.class))).thenReturn(currentOwner);
        when(memberDao.selectByWorkspaceAndUserForUpdate("workspace-a", USER_ID))
                .thenReturn(currentOwner);
        when(memberDao.selectByWorkspaceAndUserForUpdate("workspace-a", 2002L)).thenReturn(null);
        SysUserEntity invitee = user(2002L, "bob");
        when(sysUserDao.selectOne(any(Wrapper.class))).thenReturn(invitee);
        when(sysUserDao.selectByIdForUpdate(2002L)).thenReturn(invitee);
        List<WorkspaceMemberEntity> activeMembers = List.of(
                currentOwner,
                member("workspace-a", 3001L, WorkspaceService.ROLE_MEMBER),
                member("workspace-a", 3002L, WorkspaceService.ROLE_MEMBER),
                member("workspace-a", 3003L, WorkspaceService.ROLE_MEMBER));
        when(memberDao.selectActiveByWorkspaceForUpdate("workspace-a")).thenReturn(activeMembers);
        WorkspaceInvitationEntity pending = new WorkspaceInvitationEntity();
        pending.setId("pending-link");
        pending.setInviteeUserId(4004L);
        when(invitationDao.selectPendingTargetedForUpdate(any(String.class), any(Long.class), any(Date.class)))
                .thenReturn(null);
        when(invitationDao.selectPendingByWorkspaceForUpdate(any(String.class), any(Date.class)))
                .thenReturn(List.of(pending));

        try (MockedStatic<SecurityUser> ignored = mockSecurityUser(USER_ID, false)) {
            assertThrows(RenException.class, () -> workspaceService.invite("workspace-a", dto));
        }
    }

    @Test
    void repeatedInviteReusesPendingSlotInsteadOfCreatingDuplicate() {
        WorkspaceInviteDTO dto = new WorkspaceInviteDTO();
        dto.setUsername("bob");
        dto.setRole(WorkspaceService.ROLE_ADMIN);
        SysUserEntity invitee = user(2002L, "bob");
        WorkspaceInvitationEntity existing = new WorkspaceInvitationEntity();
        existing.setId("invitation-a");
        existing.setWorkspaceId("workspace-a");
        existing.setInviteeUserId(2002L);
        existing.setInviteeUsername("bob");
        existing.setRole(WorkspaceService.ROLE_MEMBER);
        String previousTokenHash = sha256("previous-targeted-token-1234567890");
        existing.setTokenHash(previousTokenHash);
        existing.setStatus("PENDING");
        existing.setExpiresAt(new Date(System.currentTimeMillis() + 60_000));

        WorkspaceMemberEntity currentOwner = member("workspace-a", USER_ID, WorkspaceService.ROLE_OWNER);
        when(memberDao.selectOne(any(Wrapper.class))).thenReturn(currentOwner);
        when(memberDao.selectByWorkspaceAndUserForUpdate("workspace-a", USER_ID))
                .thenReturn(currentOwner);
        when(memberDao.selectByWorkspaceAndUserForUpdate("workspace-a", 2002L)).thenReturn(null);
        when(sysUserDao.selectOne(any(Wrapper.class))).thenReturn(invitee);
        when(sysUserDao.selectByIdForUpdate(2002L)).thenReturn(invitee);
        when(invitationDao.selectPendingTargetedForUpdate(any(String.class), any(Long.class), any(Date.class)))
                .thenReturn(existing);
        when(workspaceDao.lockByIdForInvitation("workspace-a"))
                .thenReturn(workspace("workspace-a", USER_ID));

        try (MockedStatic<SecurityUser> ignored = mockSecurityUser(USER_ID, false)) {
            var result = workspaceService.invite("workspace-a", dto);
            assertNotNull(result.getCode());
            assertEquals(43, result.getCode().length());
            assertEquals(sha256(result.getCode()), existing.getTokenHash());
        }

        verify(invitationDao).updateById(existing);
        verify(invitationDao, never()).insert(any(WorkspaceInvitationEntity.class));
        ArgumentCaptor<UpdateWrapper<WorkspaceInvitationEntity>> revokeCaptor = updateWrapperCaptor();
        verify(invitationDao).update(isNull(), revokeCaptor.capture());
        UpdateWrapper<WorkspaceInvitationEntity> revoke = revokeCaptor.getValue();
        assertTrue(revoke.getSqlSet().contains("status"));
        assertTrue(revoke.getCustomSqlSegment().contains("id"));
        assertTrue(revoke.getParamNameValuePairs().containsValue("REVOKED"));
        assertTrue(revoke.getParamNameValuePairs().containsValue(existing.getId()));
        assertEquals(WorkspaceService.ROLE_ADMIN, existing.getRole());
        assertNull(existing.getCode());
        assertNotEquals(previousTokenHash, existing.getTokenHash());
        assertEquals(USER_ID, existing.getCreator());
        var lockOrder = inOrder(sysUserDao, workspaceDao, memberDao, invitationDao);
        lockOrder.verify(sysUserDao).selectByIdForUpdate(USER_ID);
        lockOrder.verify(sysUserDao).selectByIdForUpdate(2002L);
        lockOrder.verify(workspaceDao).lockByIdForInvitation("workspace-a");
        lockOrder.verify(memberDao).selectByWorkspaceAndUserForUpdate("workspace-a", USER_ID);
        lockOrder.verify(memberDao).selectByWorkspaceAndUserForUpdate("workspace-a", 2002L);
        lockOrder.verify(invitationDao).selectPendingTargetedForUpdate(
                org.mockito.ArgumentMatchers.eq("workspace-a"),
                org.mockito.ArgumentMatchers.eq(2002L),
                any(Date.class));
    }

    @Test
    void targetedInviteStoresOnlyHashAndReturnsRawTokenOnce() {
        WorkspaceInviteDTO dto = new WorkspaceInviteDTO();
        dto.setUsername("bob");
        dto.setRole(WorkspaceService.ROLE_MEMBER);
        SysUserEntity invitee = user(2002L, "bob");
        WorkspaceMemberEntity currentOwner = member("workspace-a", USER_ID, WorkspaceService.ROLE_OWNER);
        when(memberDao.selectOne(any(Wrapper.class))).thenReturn(currentOwner);
        when(memberDao.selectByWorkspaceAndUserForUpdate("workspace-a", USER_ID))
                .thenReturn(currentOwner);
        when(memberDao.selectByWorkspaceAndUserForUpdate("workspace-a", 2002L)).thenReturn(null);
        when(sysUserDao.selectOne(any(Wrapper.class))).thenReturn(invitee);
        when(sysUserDao.selectByIdForUpdate(2002L)).thenReturn(invitee);
        when(invitationDao.selectPendingTargetedForUpdate(any(String.class), any(Long.class), any(Date.class)))
                .thenReturn(null);
        when(workspaceDao.lockByIdForInvitation("workspace-a"))
                .thenReturn(workspace("workspace-a", USER_ID));
        when(memberDao.selectActiveByWorkspaceForUpdate("workspace-a")).thenReturn(List.of(currentOwner));
        when(invitationDao.selectPendingByWorkspaceForUpdate(any(String.class), any(Date.class)))
                .thenReturn(List.of());

        xiaozhi.modules.workspace.vo.WorkspaceInvitationVO result;
        try (MockedStatic<SecurityUser> ignored = mockSecurityUser(USER_ID, false)) {
            result = workspaceService.invite("workspace-a", dto);
        }

        ArgumentCaptor<WorkspaceInvitationEntity> captor = ArgumentCaptor.forClass(WorkspaceInvitationEntity.class);
        verify(invitationDao).insert(captor.capture());
        WorkspaceInvitationEntity saved = captor.getValue();
        assertEquals("TARGETED", saved.getInvitationType());
        assertNull(saved.getCode());
        assertNotNull(result.getCode());
        assertEquals(43, result.getCode().length());
        assertEquals(sha256(result.getCode()), saved.getTokenHash());
        var lockOrder = inOrder(sysUserDao, workspaceDao);
        lockOrder.verify(sysUserDao).selectByIdForUpdate(USER_ID);
        lockOrder.verify(sysUserDao).selectByIdForUpdate(2002L);
        lockOrder.verify(workspaceDao).lockByIdForInvitation("workspace-a");
    }

    @Test
    void inviteRechecksWritePermissionAfterWorkspaceLock() {
        WorkspaceInviteDTO dto = new WorkspaceInviteDTO();
        dto.setRole(WorkspaceService.ROLE_MEMBER);
        when(memberDao.selectOne(any(Wrapper.class)))
                .thenReturn(member("workspace-a", USER_ID, WorkspaceService.ROLE_OWNER));
        when(workspaceDao.lockByIdForInvitation("workspace-a"))
                .thenReturn(workspace("workspace-a", USER_ID));
        when(memberDao.selectByWorkspaceAndUserForUpdate("workspace-a", USER_ID))
                .thenReturn(member("workspace-a", USER_ID, WorkspaceService.ROLE_MEMBER));

        try (MockedStatic<SecurityUser> ignored = mockSecurityUser(USER_ID, false)) {
            assertThrows(RenException.class, () -> workspaceService.invite("workspace-a", dto));
        }

        var lockOrder = inOrder(memberDao, workspaceDao);
        lockOrder.verify(memberDao).selectOne(any(Wrapper.class));
        lockOrder.verify(workspaceDao).lockByIdForInvitation("workspace-a");
        lockOrder.verify(memberDao).selectByWorkspaceAndUserForUpdate("workspace-a", USER_ID);
        verify(invitationDao, never()).insert(any(WorkspaceInvitationEntity.class));
    }

    @Test
    void blankUsernameCreatesFreshHashedLinkInvitationForTwentyFourHours() throws Exception {
        WorkspaceInviteDTO dto = new WorkspaceInviteDTO();
        dto.setRole(WorkspaceService.ROLE_MEMBER);
        WorkspaceMemberEntity currentOwner = member("workspace-a", USER_ID, WorkspaceService.ROLE_OWNER);
        when(memberDao.selectOne(any(Wrapper.class))).thenReturn(currentOwner);
        when(memberDao.selectByWorkspaceAndUserForUpdate("workspace-a", USER_ID))
                .thenReturn(currentOwner);
        when(workspaceDao.lockByIdForInvitation("workspace-a"))
                .thenReturn(workspace("workspace-a", USER_ID));
        when(memberDao.selectActiveByWorkspaceForUpdate("workspace-a")).thenReturn(List.of(currentOwner));
        when(invitationDao.selectPendingByWorkspaceForUpdate(any(String.class), any(Date.class)))
                .thenReturn(List.of());

        long before = System.currentTimeMillis();
        xiaozhi.modules.workspace.vo.WorkspaceInvitationVO result;
        try (MockedStatic<SecurityUser> ignored = mockSecurityUser(USER_ID, false)) {
            result = workspaceService.invite("workspace-a", dto);
        }

        ArgumentCaptor<WorkspaceInvitationEntity> captor = ArgumentCaptor.forClass(WorkspaceInvitationEntity.class);
        verify(invitationDao).insert(captor.capture());
        WorkspaceInvitationEntity saved = captor.getValue();
        assertEquals("LINK", saved.getInvitationType());
        assertNull(saved.getCode());
        assertNull(saved.getInviteeUsername());
        assertNull(saved.getInviteeUserId());
        assertNotNull(result.getCode());
        assertEquals(43, result.getCode().length());
        assertEquals(sha256(result.getCode()), saved.getTokenHash());
        assertTrue(saved.getExpiresAt().getTime() >= before + 24L * 60 * 60 * 1000 - 2_000);
        assertTrue(saved.getExpiresAt().getTime() <= before + 24L * 60 * 60 * 1000 + 2_000);
        assertEquals(Boolean.TRUE, result.getLinkInvitation());
        verify(workspaceDao).lockByIdForInvitation("workspace-a");
    }

    @Test
    void disabledCreatorCannotCreateAnotherLinkInvitation() {
        WorkspaceInviteDTO dto = new WorkspaceInviteDTO();
        dto.setRole(WorkspaceService.ROLE_MEMBER);
        when(memberDao.selectOne(any(Wrapper.class)))
                .thenReturn(member("workspace-a", USER_ID, WorkspaceService.ROLE_OWNER));
        SysUserEntity disabledCreator = user(USER_ID, "alice");
        disabledCreator.setStatus(0);
        when(sysUserDao.selectByIdForUpdate(USER_ID)).thenReturn(disabledCreator);

        try (MockedStatic<SecurityUser> ignored = mockSecurityUser(USER_ID, false)) {
            assertThrows(RenException.class, () -> workspaceService.invite("workspace-a", dto));
        }

        verify(workspaceDao, never()).lockByIdForInvitation(any(String.class));
        verify(invitationDao, never()).insert(any(WorkspaceInvitationEntity.class));
    }

    @Test
    void everyLinkInviteCreatesANewTokenInsteadOfReusingPendingInvite() {
        WorkspaceInviteDTO dto = new WorkspaceInviteDTO();
        dto.setRole(WorkspaceService.ROLE_ADMIN);
        WorkspaceMemberEntity currentOwner = member("workspace-a", USER_ID, WorkspaceService.ROLE_OWNER);
        when(memberDao.selectOne(any(Wrapper.class))).thenReturn(currentOwner);
        when(memberDao.selectByWorkspaceAndUserForUpdate("workspace-a", USER_ID))
                .thenReturn(currentOwner);
        when(workspaceDao.lockByIdForInvitation("workspace-a"))
                .thenReturn(workspace("workspace-a", USER_ID));
        when(memberDao.selectActiveByWorkspaceForUpdate("workspace-a")).thenReturn(List.of(currentOwner));
        when(invitationDao.selectPendingByWorkspaceForUpdate(any(String.class), any(Date.class)))
                .thenReturn(List.of());

        try (MockedStatic<SecurityUser> ignored = mockSecurityUser(USER_ID, false)) {
            workspaceService.invite("workspace-a", dto);
        }

        verify(invitationDao).insert(any(WorkspaceInvitationEntity.class));
        verify(invitationDao, never()).selectOne(any(Wrapper.class));
    }

    @Test
    void pendingInvitationListNeverReturnsBearerCode() {
        WorkspaceInvitationEntity invitation = linkInvitation("raw-link-token-value-12345", 2002L);
        invitation.setCode("must-not-leak");
        when(memberDao.selectOne(any(Wrapper.class)))
                .thenReturn(member("workspace-a", USER_ID, WorkspaceService.ROLE_OWNER));
        when(memberDao.selectList(any(Wrapper.class))).thenReturn(List.of(
                member("workspace-a", USER_ID, WorkspaceService.ROLE_OWNER)));
        when(invitationDao.selectList(any(Wrapper.class))).thenReturn(List.of(invitation));
        when(workspaceDao.selectById("workspace-a")).thenReturn(workspace("workspace-a", USER_ID));

        try (MockedStatic<SecurityUser> ignored = mockSecurityUser(USER_ID, false)) {
            var result = workspaceService.listPendingInvitations("workspace-a");
            assertEquals(1, result.size());
            assertNull(result.get(0).getCode());
            assertEquals("LINK", result.get(0).getInvitationType());
            assertEquals(Boolean.TRUE, result.get(0).getLinkInvitation());
        }
    }

    @Test
    void previewReturnsOnlyLimitedInvitationFields() {
        String token = "preview-link-token-value-123456789012";
        WorkspaceInvitationEntity invitation = linkInvitation(token, 2002L);
        when(invitationDao.selectOne(any(Wrapper.class))).thenReturn(invitation);
        when(workspaceDao.selectById("workspace-a")).thenReturn(workspace("workspace-a", 2002L));
        when(memberDao.selectOne(any(Wrapper.class)))
                .thenReturn(member("workspace-a", 2002L, WorkspaceService.ROLE_ADMIN));

        try (MockedStatic<SecurityUser> ignored = mockSecurityUser(USER_ID, false)) {
            var result = workspaceService.previewInvitation(token);
            assertEquals("workspace-a", result.getWorkspaceName());
            assertEquals(WorkspaceService.ROLE_MEMBER, result.getRole());
            assertNull(result.getStatus());
            assertNull(result.getLinkInvitation());
            assertNull(result.getInvitationType());
            assertNull(result.getId());
            assertNull(result.getCode());
            assertNull(result.getWorkspaceId());
            assertNull(result.getInviteeUsername());
        }
    }

    @Test
    void disabledCreatorInvitationCannotBePreviewedOrAccepted() {
        String token = "disabled-creator-token-value-123456789";
        WorkspaceInvitationEntity invitation = linkInvitation(token, 2002L);
        WorkspaceMemberEntity creator = member("workspace-a", 2002L, WorkspaceService.ROLE_ADMIN);
        SysUserEntity disabledCreator = user(2002L, "creator");
        disabledCreator.setStatus(0);
        when(invitationDao.selectOne(any(Wrapper.class))).thenReturn(invitation);
        when(workspaceDao.selectById("workspace-a")).thenReturn(workspace("workspace-a", 2002L));
        when(memberDao.selectOne(any(Wrapper.class))).thenReturn(creator);
        when(sysUserDao.selectById(2002L)).thenReturn(disabledCreator);

        try (MockedStatic<SecurityUser> ignored = mockSecurityUser(USER_ID, false)) {
            assertThrows(RenException.class, () -> workspaceService.previewInvitation(token));
        }

        when(sysUserDao.selectByIdForUpdate(2002L)).thenReturn(disabledCreator);
        when(workspaceDao.lockByIdForInvitation("workspace-a"))
                .thenReturn(workspace("workspace-a", 2002L));
        when(invitationDao.selectByIdForUpdate(invitation.getId())).thenReturn(invitation);
        when(memberDao.selectByWorkspaceAndUserForUpdate("workspace-a", USER_ID)).thenReturn(null);
        try (MockedStatic<SecurityUser> ignored = mockSecurityUser(USER_ID, false)) {
            assertThrows(RenException.class, () -> workspaceService.acceptInvitation(token));
        }

        verify(memberDao, never()).insert(any(WorkspaceMemberEntity.class));
        verify(invitationDao, never()).updateById(any(WorkspaceInvitationEntity.class));
    }

    @Test
    void databaseHashCannotBeSubmittedAsInvitationBearer() throws Exception {
        String databaseHash = sha256("preview-link-token-value-123456789012");
        try (MockedStatic<SecurityUser> ignored = mockSecurityUser(USER_ID, false)) {
            assertThrows(RenException.class, () -> workspaceService.previewInvitation(databaseHash));
        }
        verify(invitationDao, never()).selectOne(any(Wrapper.class));
    }

    @Test
    void linkInvitationAcceptsFirstNonMemberAndRecordsClaimant() {
        String token = "accept-link-token-value-1234567890123";
        WorkspaceInvitationEntity invitation = linkInvitation(token, 2002L);
        when(invitationDao.selectOne(any(Wrapper.class))).thenReturn(invitation);
        when(invitationDao.selectByIdForUpdate(invitation.getId())).thenReturn(invitation);
        when(workspaceDao.lockByIdForInvitation("workspace-a"))
                .thenReturn(workspace("workspace-a", 2002L));
        when(memberDao.selectByWorkspaceAndUserForUpdate("workspace-a", USER_ID)).thenReturn(null);
        WorkspaceMemberEntity creator = member("workspace-a", 2002L, WorkspaceService.ROLE_OWNER);
        when(memberDao.selectByWorkspaceAndUserForUpdate("workspace-a", 2002L)).thenReturn(creator);
        when(memberDao.selectActiveByWorkspaceForUpdate("workspace-a")).thenReturn(List.of(creator));
        SysUserEntity claimant = user(USER_ID, "alice");
        when(sysUserDao.selectByIdForUpdate(USER_ID)).thenReturn(claimant);

        WorkspaceVO result;
        try (MockedStatic<SecurityUser> ignored = mockSecurityUser(USER_ID, false)) {
            result = workspaceService.acceptInvitation(token);
        }

        ArgumentCaptor<WorkspaceMemberEntity> memberCaptor = ArgumentCaptor.forClass(WorkspaceMemberEntity.class);
        verify(memberDao).insert(memberCaptor.capture());
        assertEquals(USER_ID, memberCaptor.getValue().getUserId());
        assertEquals(WorkspaceService.ROLE_MEMBER, memberCaptor.getValue().getRole());
        assertEquals(USER_ID, invitation.getAcceptedByUserId());
        assertEquals(USER_ID, invitation.getInviteeUserId());
        assertEquals("alice", invitation.getInviteeUsername());
        assertEquals("ACCEPTED", invitation.getStatus());
        assertEquals(WorkspaceService.ROLE_MEMBER, result.getCurrentUserRole());
        var lockOrder = inOrder(sysUserDao, workspaceDao, invitationDao);
        lockOrder.verify(sysUserDao).selectByIdForUpdate(USER_ID);
        lockOrder.verify(sysUserDao).selectByIdForUpdate(2002L);
        lockOrder.verify(workspaceDao).lockByIdForInvitation("workspace-a");
        lockOrder.verify(invitationDao).selectByIdForUpdate(invitation.getId());
    }

    @Test
    void existingActiveMemberDoesNotConsumePendingLinkInvitation() {
        String token = "member-link-token-value-1234567890123";
        WorkspaceInvitationEntity invitation = linkInvitation(token, 2002L);
        WorkspaceMemberEntity existingMember = member("workspace-a", USER_ID, WorkspaceService.ROLE_MEMBER);
        when(invitationDao.selectOne(any(Wrapper.class))).thenReturn(invitation);
        when(invitationDao.selectByIdForUpdate(invitation.getId())).thenReturn(invitation);
        when(workspaceDao.lockByIdForInvitation("workspace-a"))
                .thenReturn(workspace("workspace-a", 2002L));
        when(memberDao.selectByWorkspaceAndUserForUpdate("workspace-a", USER_ID))
                .thenReturn(existingMember);
        when(memberDao.selectByWorkspaceAndUserForUpdate("workspace-a", 2002L))
                .thenReturn(member("workspace-a", 2002L, WorkspaceService.ROLE_OWNER));
        when(sysUserDao.selectByIdForUpdate(USER_ID)).thenReturn(user(USER_ID, "alice"));

        try (MockedStatic<SecurityUser> ignored = mockSecurityUser(USER_ID, false)) {
            WorkspaceVO result = workspaceService.acceptInvitation(token);
            assertEquals(WorkspaceService.ROLE_MEMBER, result.getCurrentUserRole());
        }

        assertEquals("PENDING", invitation.getStatus());
        assertNull(invitation.getAcceptedByUserId());
        verify(invitationDao, never()).updateById(any(WorkspaceInvitationEntity.class));
        verify(memberDao, never()).insert(any(WorkspaceMemberEntity.class));
    }

    @Test
    void revokedCreatorLinkInvitationCannotBeClaimed() {
        String token = "orphan-link-token-value-1234567890123";
        WorkspaceInvitationEntity invitation = linkInvitation(token, 2002L);
        invitation.setStatus("REVOKED");
        when(invitationDao.selectOne(any(Wrapper.class))).thenReturn(invitation);
        when(invitationDao.selectByIdForUpdate(invitation.getId())).thenReturn(invitation);
        when(workspaceDao.lockByIdForInvitation("workspace-a"))
                .thenReturn(workspace("workspace-a", 2002L));
        when(memberDao.selectByWorkspaceAndUserForUpdate("workspace-a", USER_ID)).thenReturn(null);
        when(sysUserDao.selectByIdForUpdate(USER_ID)).thenReturn(user(USER_ID, "alice"));

        try (MockedStatic<SecurityUser> ignored = mockSecurityUser(USER_ID, false)) {
            assertThrows(RenException.class, () -> workspaceService.acceptInvitation(token));
        }

        verify(memberDao, never()).insert(any(WorkspaceMemberEntity.class));
        verify(invitationDao, never()).updateById(any(WorkspaceInvitationEntity.class));
        verify(memberDao, never()).selectByWorkspaceAndUserForUpdate("workspace-a", 2002L);
    }

    @Test
    void targetedInvitationIsInvalidWhenCreatorNoLongerManagesWorkspace() {
        String token = "orphan-targeted-token-value-123456789";
        WorkspaceInvitationEntity invitation = targetedInvitation(token, USER_ID, 2002L);
        when(invitationDao.selectOne(any(Wrapper.class))).thenReturn(invitation);
        when(workspaceDao.selectById("workspace-a")).thenReturn(workspace("workspace-a", 2002L));
        when(memberDao.selectOne(any(Wrapper.class))).thenReturn(null);

        try (MockedStatic<SecurityUser> ignored = mockSecurityUser(USER_ID, false)) {
            assertThrows(RenException.class, () -> workspaceService.previewInvitation(token));
        }

        verify(sysUserDao, never()).selectById(any());
    }

    @Test
    void targetedInvitationRejectsWrongUserIdEvenWhenUsernameMatches() {
        String token = "targeted-token-value-123456789012345";
        WorkspaceInvitationEntity invitation = targetedInvitation(token, 3003L, 2002L);
        invitation.setInviteeUsername("alice");
        when(invitationDao.selectOne(any(Wrapper.class))).thenReturn(invitation);
        when(invitationDao.selectByIdForUpdate(invitation.getId())).thenReturn(invitation);
        when(workspaceDao.lockByIdForInvitation("workspace-a"))
                .thenReturn(workspace("workspace-a", 2002L));
        when(memberDao.selectByWorkspaceAndUserForUpdate("workspace-a", USER_ID)).thenReturn(null);
        when(memberDao.selectByWorkspaceAndUserForUpdate("workspace-a", 2002L))
                .thenReturn(member("workspace-a", 2002L, WorkspaceService.ROLE_OWNER));
        SysUserEntity caller = user(USER_ID, "alice");
        when(sysUserDao.selectByIdForUpdate(USER_ID)).thenReturn(caller);

        try (MockedStatic<SecurityUser> ignored = mockSecurityUser(USER_ID, false)) {
            assertThrows(RenException.class, () -> workspaceService.acceptInvitation(token));
        }

        verify(memberDao, never()).insert(any(WorkspaceMemberEntity.class));
        verify(invitationDao, never()).updateById(any(WorkspaceInvitationEntity.class));
        verify(sysUserDao, never()).selectById(USER_ID);
    }

    @Test
    void claimedLinkIsIdempotentOnlyForItsClaimant() {
        String token = "claimed-link-token-value-123456789012";
        WorkspaceInvitationEntity invitation = linkInvitation(token, 2002L);
        invitation.setStatus("ACCEPTED");
        invitation.setAcceptedByUserId(USER_ID);
        WorkspaceMemberEntity existingMember = member("workspace-a", USER_ID, WorkspaceService.ROLE_MEMBER);
        when(invitationDao.selectOne(any(Wrapper.class))).thenReturn(invitation);
        when(invitationDao.selectByIdForUpdate(invitation.getId())).thenReturn(invitation);
        when(workspaceDao.lockByIdForInvitation("workspace-a"))
                .thenReturn(workspace("workspace-a", 2002L));
        when(memberDao.selectByWorkspaceAndUserForUpdate("workspace-a", USER_ID))
                .thenReturn(existingMember);
        when(sysUserDao.selectByIdForUpdate(USER_ID)).thenReturn(user(USER_ID, "alice"));

        try (MockedStatic<SecurityUser> ignored = mockSecurityUser(USER_ID, false)) {
            assertEquals(WorkspaceService.ROLE_MEMBER,
                    workspaceService.acceptInvitation(token).getCurrentUserRole());
        }

        verify(invitationDao, never()).updateById(any(WorkspaceInvitationEntity.class));

        invitation.setAcceptedByUserId(3003L);
        try (MockedStatic<SecurityUser> ignored = mockSecurityUser(USER_ID, false)) {
            assertThrows(RenException.class, () -> workspaceService.acceptInvitation(token));
        }
    }

    @Test
    void acceptInvitationReactivatesDisabledMemberInsteadOfInsertingDuplicate() {
        String token = "legacy-invite-code-123456";
        WorkspaceInvitationEntity invitation = new WorkspaceInvitationEntity();
        invitation.setId("invitation-a");
        invitation.setWorkspaceId("workspace-a");
        invitation.setInviteeUsername("stale-username");
        invitation.setInviteeUserId(USER_ID);
        invitation.setRole(WorkspaceService.ROLE_ADMIN);
        invitation.setInvitationType("TARGETED");
        invitation.setTokenHash(sha256(token));
        invitation.setStatus("PENDING");
        invitation.setExpiresAt(new Date(System.currentTimeMillis() + 60_000));
        invitation.setCreator(2002L);

        WorkspaceMemberEntity disabledMember = member(
                "workspace-a", USER_ID, WorkspaceService.ROLE_MEMBER);
        disabledMember.setStatus(0);
        SysUserEntity user = user(USER_ID, "alice");
        WorkspaceEntity workspace = workspace("workspace-a", 2002L);

        when(invitationDao.selectOne(any(Wrapper.class))).thenReturn(invitation);
        when(invitationDao.selectByIdForUpdate("invitation-a")).thenReturn(invitation);
        when(sysUserDao.selectByIdForUpdate(USER_ID)).thenReturn(user);
        when(memberDao.selectByWorkspaceAndUserForUpdate("workspace-a", USER_ID))
                .thenReturn(disabledMember);
        WorkspaceMemberEntity creator = member("workspace-a", 2002L, WorkspaceService.ROLE_OWNER);
        when(memberDao.selectByWorkspaceAndUserForUpdate("workspace-a", 2002L)).thenReturn(creator);
        when(memberDao.selectActiveByWorkspaceForUpdate("workspace-a")).thenReturn(List.of(creator));
        when(workspaceDao.lockByIdForInvitation("workspace-a")).thenReturn(workspace);

        WorkspaceVO result;
        try (MockedStatic<SecurityUser> ignored = mockSecurityUser(USER_ID, false)) {
            result = workspaceService.acceptInvitation(token);
        }

        ArgumentCaptor<WorkspaceMemberEntity> memberCaptor = ArgumentCaptor.forClass(WorkspaceMemberEntity.class);
        verify(memberDao).updateById(memberCaptor.capture());
        verify(memberDao, never()).insert(any(WorkspaceMemberEntity.class));
        assertEquals(disabledMember.getId(), memberCaptor.getValue().getId());
        assertEquals(1, memberCaptor.getValue().getStatus());
        assertEquals(WorkspaceService.ROLE_ADMIN, memberCaptor.getValue().getRole());
        assertEquals("ACCEPTED", invitation.getStatus());
        assertEquals(WorkspaceService.ROLE_ADMIN, result.getCurrentUserRole());
        var lockOrder = inOrder(sysUserDao, workspaceDao, invitationDao);
        lockOrder.verify(sysUserDao).selectByIdForUpdate(USER_ID);
        lockOrder.verify(workspaceDao).lockByIdForInvitation("workspace-a");
        lockOrder.verify(invitationDao).selectByIdForUpdate("invitation-a");
    }

    @Test
    void ownerCannotLeaveWorkspaceDirectly() {
        when(workspaceDao.lockByIdForInvitation("workspace-a"))
                .thenReturn(workspace("workspace-a", 2002L));
        when(memberDao.selectByWorkspaceAndUserForUpdate("workspace-a", USER_ID))
                .thenReturn(member("workspace-a", USER_ID, WorkspaceService.ROLE_OWNER));

        try (MockedStatic<SecurityUser> ignored = mockSecurityUser(USER_ID, false)) {
            assertThrows(RenException.class, () -> workspaceService.leaveWorkspace("workspace-a"));
        }
    }

    @Test
    void prepareUserDeletionRejectsLastOwnerMembership() {
        WorkspaceMemberEntity lastOwner = member("workspace-a", USER_ID, WorkspaceService.ROLE_OWNER);
        when(sysUserDao.selectByIdForUpdate(USER_ID)).thenReturn(user(USER_ID, "alice"));
        when(workspaceDao.selectOwnedByUserForUpdate(USER_ID)).thenReturn(List.of());
        when(memberDao.selectList(any(Wrapper.class))).thenReturn(List.of(lastOwner));
        when(invitationDao.selectPendingWorkspaceIdsRelatedToUser(USER_ID)).thenReturn(List.of());
        when(workspaceDao.lockByIdForInvitation("workspace-a"))
                .thenReturn(workspace("workspace-a", 2002L));
        when(memberDao.selectActiveByUserForUpdate(USER_ID)).thenReturn(List.of(lastOwner));
        when(memberDao.selectActiveByWorkspaceForUpdate("workspace-a")).thenReturn(List.of(lastOwner));

        assertThrows(RenException.class, () -> workspaceService.prepareUserDeletion(USER_ID));
    }

    @Test
    void prepareUserDeletionDisablesMembershipsWhenUserIsNotLastOwner() {
        WorkspaceMemberEntity memberB = member("workspace-b", USER_ID, WorkspaceService.ROLE_MEMBER);
        WorkspaceMemberEntity ownerA = member("workspace-a", USER_ID, WorkspaceService.ROLE_OWNER);
        WorkspaceMemberEntity otherOwnerA = member("workspace-a", 2002L, WorkspaceService.ROLE_OWNER);
        when(sysUserDao.selectByIdForUpdate(USER_ID)).thenReturn(user(USER_ID, "alice"));
        when(workspaceDao.selectOwnedByUserForUpdate(USER_ID)).thenReturn(List.of());
        when(memberDao.selectList(any(Wrapper.class))).thenReturn(List.of(memberB, ownerA));
        when(invitationDao.selectPendingWorkspaceIdsRelatedToUser(USER_ID))
                .thenReturn(List.of("workspace-c", "workspace-a"));
        when(workspaceDao.lockByIdForInvitation("workspace-a"))
                .thenReturn(workspace("workspace-a", 3003L));
        when(workspaceDao.lockByIdForInvitation("workspace-b"))
                .thenReturn(workspace("workspace-b", 3003L));
        when(workspaceDao.lockByIdForInvitation("workspace-c"))
                .thenReturn(workspace("workspace-c", 3003L));
        when(memberDao.selectActiveByUserForUpdate(USER_ID)).thenReturn(List.of(ownerA, memberB));
        when(memberDao.selectActiveByWorkspaceForUpdate("workspace-a"))
                .thenReturn(List.of(ownerA, otherOwnerA));

        workspaceService.prepareUserDeletion(USER_ID);

        verify(memberDao).update(any(), any());
        var lockOrder = inOrder(sysUserDao, workspaceDao);
        lockOrder.verify(sysUserDao).selectByIdForUpdate(USER_ID);
        lockOrder.verify(workspaceDao).selectOwnedByUserForUpdate(USER_ID);
        lockOrder.verify(workspaceDao).lockByIdForInvitation("workspace-a");
        lockOrder.verify(workspaceDao).lockByIdForInvitation("workspace-b");
        lockOrder.verify(workspaceDao).lockByIdForInvitation("workspace-c");
        verify(invitationDao, org.mockito.Mockito.times(6))
                .update(isNull(), any(UpdateWrapper.class));
    }

    @Test
    void prepareUsersDisabledLocksAllUsersThenWorkspacesBeforeRevokingInvitations() {
        when(invitationDao.selectPendingWorkspaceIdsCreatedByUser(USER_ID))
                .thenReturn(List.of("workspace-b", "workspace-a"));
        when(invitationDao.selectPendingWorkspaceIdsCreatedByUser(2002L))
                .thenReturn(List.of("workspace-c", "workspace-a"));
        when(workspaceDao.lockByIdForInvitation("workspace-a"))
                .thenReturn(workspace("workspace-a", USER_ID));
        when(workspaceDao.lockByIdForInvitation("workspace-b"))
                .thenReturn(workspace("workspace-b", USER_ID));
        when(workspaceDao.lockByIdForInvitation("workspace-c"))
                .thenReturn(workspace("workspace-c", 2002L));

        workspaceService.prepareUsersDisabled(List.of(2002L, USER_ID, 2002L));

        var lockOrder = inOrder(sysUserDao, invitationDao, workspaceDao);
        lockOrder.verify(sysUserDao).selectByIdForUpdate(USER_ID);
        lockOrder.verify(sysUserDao).selectByIdForUpdate(2002L);
        lockOrder.verify(invitationDao).selectPendingWorkspaceIdsCreatedByUser(USER_ID);
        lockOrder.verify(invitationDao).selectPendingWorkspaceIdsCreatedByUser(2002L);
        lockOrder.verify(workspaceDao).lockByIdForInvitation("workspace-a");
        lockOrder.verify(workspaceDao).lockByIdForInvitation("workspace-b");
        lockOrder.verify(workspaceDao).lockByIdForInvitation("workspace-c");
        lockOrder.verify(invitationDao, org.mockito.Mockito.times(4))
                .update(isNull(), any(UpdateWrapper.class));
    }

    @Test
    void fixedCreatorCannotBeRemovedDowngradedOrDeletedEvenWithAnotherOwner() {
        WorkspaceEntity fixedOwnerWorkspace = workspace("workspace-a", 2002L);
        WorkspaceMemberEntity currentOwner = member("workspace-a", USER_ID, WorkspaceService.ROLE_OWNER);
        when(workspaceDao.lockByIdForInvitation("workspace-a")).thenReturn(fixedOwnerWorkspace);
        when(memberDao.selectOne(any(Wrapper.class))).thenReturn(currentOwner);
        when(memberDao.selectByWorkspaceAndUserForUpdate("workspace-a", USER_ID))
                .thenReturn(currentOwner);
        when(sysUserDao.selectByIdForUpdate(2002L)).thenReturn(user(2002L, "fixed-owner"));
        when(workspaceDao.selectOwnedByUserForUpdate(2002L)).thenReturn(List.of(fixedOwnerWorkspace));
        WorkspaceRoleUpdateDTO dto=new WorkspaceRoleUpdateDTO();dto.setRole(WorkspaceService.ROLE_MEMBER);
        try(MockedStatic<SecurityUser> ignored=mockSecurityUser(USER_ID,false)) {
            assertThrows(RenException.class,()->workspaceService.removeMember("workspace-a",2002L));
            assertThrows(RenException.class,()->workspaceService.updateMemberRole("workspace-a",2002L,dto));
            assertThrows(RenException.class,()->workspaceService.prepareUserDeletion(2002L));
        }
        verify(memberDao,never()).update(any(),any());
    }

    @Test
    void fixedCreatorCannotLeaveEvenIfMembershipRoleWasChanged() {
        when(workspaceDao.lockByIdForInvitation("workspace-a"))
                .thenReturn(workspace("workspace-a",USER_ID));
        try(MockedStatic<SecurityUser> ignored=mockSecurityUser(USER_ID,false)) {
            assertThrows(RenException.class,()->workspaceService.leaveWorkspace("workspace-a"));
        }
    }

    @Test
    void fixedCreatorCannotDeleteAccountIfLegacyMembershipIsMissing() {
        WorkspaceEntity fixedOwnerWorkspace = workspace("workspace-a", USER_ID);
        when(sysUserDao.selectByIdForUpdate(USER_ID)).thenReturn(user(USER_ID, "alice"));
        when(workspaceDao.selectOwnedByUserForUpdate(USER_ID)).thenReturn(List.of(fixedOwnerWorkspace));
        assertThrows(RenException.class,()->workspaceService.prepareUserDeletion(USER_ID));
        verify(memberDao,never()).update(any(),any());
    }

    private MockedStatic<SecurityUser> mockSecurityUser(Long userId, boolean employee) {
        UserDetail user = new UserDetail();
        user.setId(userId);
        user.setAudience(employee ? "OPS" : "USER");
        if (employee) { user.setId(null); user.setEmployeeId("employee"); }
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
        workspace.setDefaultWorkspace(1);
        return workspace;
    }

    private SysUserEntity user(Long userId, String username) {
        SysUserEntity user = new SysUserEntity();
        user.setId(userId);
        user.setUsername(username);
        user.setStatus(1);
        return user;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private ArgumentCaptor<UpdateWrapper<WorkspaceInvitationEntity>> updateWrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(UpdateWrapper.class);
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

    private WorkspaceInvitationEntity linkInvitation(String token, Long creator) {
        WorkspaceInvitationEntity invitation = new WorkspaceInvitationEntity();
        invitation.setId("invitation-link");
        invitation.setWorkspaceId("workspace-a");
        invitation.setInvitationType("LINK");
        invitation.setTokenHash(sha256(token));
        invitation.setRole(WorkspaceService.ROLE_MEMBER);
        invitation.setStatus("PENDING");
        invitation.setExpiresAt(new Date(System.currentTimeMillis() + 60_000));
        invitation.setCreator(creator);
        return invitation;
    }

    private WorkspaceInvitationEntity targetedInvitation(String token, Long inviteeUserId, Long creator) {
        WorkspaceInvitationEntity invitation = new WorkspaceInvitationEntity();
        invitation.setId("invitation-targeted");
        invitation.setWorkspaceId("workspace-a");
        invitation.setInviteeUsername("target-user");
        invitation.setInviteeUserId(inviteeUserId);
        invitation.setInvitationType("TARGETED");
        invitation.setTokenHash(sha256(token));
        invitation.setRole(WorkspaceService.ROLE_MEMBER);
        invitation.setStatus("PENDING");
        invitation.setExpiresAt(new Date(System.currentTimeMillis() + 60_000));
        invitation.setCreator(creator);
        return invitation;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

}

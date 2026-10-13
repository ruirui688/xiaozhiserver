package xiaozhi.modules.workspace.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;

import lombok.AllArgsConstructor;
import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.service.impl.BaseServiceImpl;
import xiaozhi.common.user.UserDetail;
import xiaozhi.common.utils.HttpContextUtils;
import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.modules.sys.dao.SysUserDao;
import xiaozhi.modules.sys.entity.SysUserEntity;
import xiaozhi.modules.security.ops.OpsAccess;
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
import xiaozhi.modules.workspace.vo.WorkspaceInvitationVO;
import xiaozhi.modules.workspace.vo.WorkspaceMemberVO;
import xiaozhi.modules.workspace.vo.WorkspaceVO;

@Service
@AllArgsConstructor
public class WorkspaceServiceImpl extends BaseServiceImpl<WorkspaceDao, WorkspaceEntity> implements WorkspaceService {
    private static final int ACTIVE = 1;
    private static final String HEADER_WORKSPACE_ID = "X-Workspace-Id";
    private static final String INVITE_PENDING = "PENDING";
    private static final String INVITE_ACCEPTED = "ACCEPTED";
    private static final String INVITE_REVOKED = "REVOKED";
    private static final String INVITATION_TARGETED = "TARGETED";
    private static final String INVITATION_LINK = "LINK";
    private static final String INVITATION_INVALID = "邀请已失效";
    private static final int MAX_WORKSPACE_MEMBERS = 5;
    private static final long TARGETED_INVITATION_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000;
    private static final long LINK_INVITATION_TTL_MILLIS = 24L * 60 * 60 * 1000;
    private static final Pattern INVITATION_CODE_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{20,63}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final WorkspaceDao workspaceDao;
    private final WorkspaceMemberDao memberDao;
    private final WorkspaceInvitationDao invitationDao;
    private final SysUserDao sysUserDao;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<WorkspaceVO> listCurrentUserWorkspaces() {
        Long userId = SecurityUser.getUserId();
        ensureDefaultWorkspace(userId);
        return memberDao.selectList(new QueryWrapper<WorkspaceMemberEntity>()
                .eq("user_id", userId)
                .eq("status", ACTIVE)
                .orderByAsc("created_at"))
                .stream()
                .map(member -> toVO(workspaceDao.selectById(member.getWorkspaceId()), member))
                .filter(vo -> vo.getId() != null)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkspaceVO getCurrentWorkspace() {
        WorkspaceEntity workspace = getCurrentWorkspaceEntity();
        return toVO(workspace, getActiveMember(workspace.getId(), SecurityUser.getUserId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkspaceEntity getCurrentWorkspaceEntity() {
        Long userId = SecurityUser.getUserId();
        if (userId == null) {
            throw new RenException(ErrorCode.UNAUTHORIZED);
        }
        String requestedId = getRequestedWorkspaceId();
        if (StringUtils.isNotBlank(requestedId)) {
            WorkspaceEntity workspace = workspaceDao.selectById(requestedId);
            if (workspace == null || !Integer.valueOf(ACTIVE).equals(workspace.getStatus())) {
                throw new RenException(ErrorCode.NO_PERMISSION);
            }
            requireRead(requestedId);
            return workspace;
        }
        return ensureDefaultWorkspace(userId);
    }

    @Override
    public WorkspaceEntity getDefaultWorkspace(Long userId) {
        if (userId == null) {
            return null;
        }
        return workspaceDao.selectOne(new QueryWrapper<WorkspaceEntity>()
                .eq("owner_user_id", userId)
                .eq("default_workspace", 1)
                .last("LIMIT 1"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkspaceEntity ensureDefaultWorkspace(Long userId) {
        SysUserEntity user = sysUserDao.selectByIdForUpdate(userId);
        if (user == null) {
            throw new RenException(ErrorCode.UNAUTHORIZED);
        }
        WorkspaceEntity existing = getDefaultWorkspace(userId);
        if (existing != null) {
            // Existing memberships are authoritative; never restore a removed owner.
            return existing;
        }
        String username = user.getUsername();
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(uuid());
        workspace.setName(username + "的工作空间");
        workspace.setOwnerUserId(userId);
        workspace.setDefaultWorkspace(1);
        workspace.setStatus(ACTIVE);
        workspace.setCreator(userId);
        workspace.setUpdater(userId);
        workspace.setCreatedAt(new Date());
        workspace.setUpdatedAt(new Date());
        workspaceDao.insert(workspace);
        createMember(workspace.getId(), userId, ROLE_OWNER, userId);
        xiaozhi.common.utils.SpringContextUtils.getBean(xiaozhi.modules.platform.service.PlatformResponsibilityService.class).ensureWorkspace(workspace.getId());
        return workspace;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkspaceVO createWorkspace(WorkspaceCreateDTO dto) {
        throw new RenException("每个用户仅保留注册时默认工作空间，不能创建额外工作空间");
    }

    @Override
    public WorkspaceVO updateWorkspace(String workspaceId, WorkspaceCreateDTO dto) {
        requireWrite(workspaceId);
        WorkspaceEntity workspace = workspaceDao.selectById(workspaceId);
        if (workspace == null) {
            throw new RenException(ErrorCode.NO_PERMISSION);
        }
        workspace.setName(dto.getName().trim());
        workspaceDao.updateById(workspace);
        return toVO(workspace, getActiveMember(workspaceId, SecurityUser.getUserId()));
    }

    @Override
    public List<WorkspaceMemberVO> listMembers(String workspaceId) {
        requireRead(workspaceId);
        return memberDao.selectList(new QueryWrapper<WorkspaceMemberEntity>()
                .eq("workspace_id", workspaceId)
                .eq("status", ACTIVE)
                .orderByAsc("created_at"))
                .stream()
                .map(this::toMemberVO)
                .toList();
    }

    @Override
    public List<WorkspaceInvitationVO> listPendingInvitations(String workspaceId) {
        requireWrite(workspaceId);
        return effectivePendingInvitations(workspaceId).stream()
                .map(this::toInvitationVO)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkspaceInvitationVO invite(String workspaceId, WorkspaceInviteDTO dto) {
        requireWrite(workspaceId);
        String role = normalizeInviteRole(dto.getRole());
        Long creatorUserId = SecurityUser.getUserId();
        if (creatorUserId == null) {
            throw new RenException(ErrorCode.UNAUTHORIZED);
        }
        if (StringUtils.isBlank(dto.getUsername())) {
            SysUserEntity creator = lockUsersForUpdate(List.of(creatorUserId)).get(creatorUserId);
            requireActiveUser(creator);
            WorkspaceEntity target = workspaceDao.lockByIdForInvitation(workspaceId);
            requireWriteForUpdate(workspaceId);
            requireActiveInvitationWorkspace(target);
            return createLinkInvitation(workspaceId, role);
        }

        // User lifecycle operations lock every sys_user before any workspace. Resolve the
        // invitee, then lock creator and invitee in ID order to preserve the global order.
        String username = dto.getUsername().trim();
        SysUserEntity invitee = sysUserDao.selectOne(new QueryWrapper<SysUserEntity>()
                .eq("username", username)
                .last("LIMIT 1"));
        if (invitee == null) {
            throw new RenException("被邀请用户不存在");
        }
        TreeSet<Long> userIdsToLock = new TreeSet<>();
        userIdsToLock.add(creatorUserId);
        userIdsToLock.add(invitee.getId());
        LinkedHashMap<Long, SysUserEntity> lockedUsers = lockUsersForUpdate(userIdsToLock);
        SysUserEntity lockedCreator = lockedUsers.get(creatorUserId);
        SysUserEntity lockedInvitee = lockedUsers.get(invitee.getId());
        requireActiveUser(lockedCreator);
        if (lockedInvitee == null || !username.equals(lockedInvitee.getUsername())) {
            throw new RenException("被邀请用户不存在");
        }
        if (!Integer.valueOf(ACTIVE).equals(lockedInvitee.getStatus())) {
            throw new RenException("被邀请用户已停用");
        }

        WorkspaceEntity target = workspaceDao.lockByIdForInvitation(workspaceId);
        requireWriteForUpdate(workspaceId);
        requireActiveInvitationWorkspace(target);
        return createTargetedInvitation(workspaceId, lockedInvitee, role);
    }

    private WorkspaceInvitationVO createTargetedInvitation(String workspaceId, SysUserEntity invitee,
            String role) {
        String username = invitee.getUsername();
        if (getActiveMemberForUpdate(workspaceId, invitee.getId()) != null) {
            throw new RenException("该用户已在工作空间中");
        }
        WorkspaceInvitationEntity existing = invitationDao.selectPendingTargetedForUpdate(
                workspaceId, invitee.getId(), new Date());
        if (existing != null) {
            String token = generateInvitationToken();
            existing.setInviteeUsername(username);
            existing.setInvitationType(INVITATION_TARGETED);
            existing.setRole(role);
            existing.setCode(null);
            existing.setTokenHash(hashInvitationToken(token));
            existing.setExpiresAt(targetedInvitationExpiry());
            existing.setCreator(SecurityUser.getUserId());
            invitationDao.updateById(existing);
            revokeOtherPendingTargetedInvitations(workspaceId, invitee.getId(), existing.getId());
            return toInvitationCreationVO(existing, token);
        }
        ensureWorkspaceHasCapacityForInvite(workspaceId);
        String token = generateInvitationToken();
        WorkspaceInvitationEntity invitation = new WorkspaceInvitationEntity();
        invitation.setId(uuid());
        invitation.setWorkspaceId(workspaceId);
        invitation.setInviteeUsername(username);
        invitation.setInviteeUserId(invitee.getId());
        invitation.setInvitationType(INVITATION_TARGETED);
        invitation.setRole(role);
        invitation.setTokenHash(hashInvitationToken(token));
        invitation.setStatus(INVITE_PENDING);
        invitation.setExpiresAt(targetedInvitationExpiry());
        invitation.setCreator(SecurityUser.getUserId());
        invitationDao.insert(invitation);
        revokeOtherPendingTargetedInvitations(workspaceId, invitee.getId(), invitation.getId());
        return toInvitationCreationVO(invitation, token);
    }

    private void requireActiveInvitationWorkspace(WorkspaceEntity workspace) {
        if (workspace == null || !Integer.valueOf(ACTIVE).equals(workspace.getStatus())) {
            throw new RenException("工作空间不存在或已停用");
        }
    }

    private void revokeOtherPendingTargetedInvitations(String workspaceId, Long inviteeUserId,
            String retainedInvitationId) {
        invitationDao.update(null, new UpdateWrapper<WorkspaceInvitationEntity>()
                .eq("workspace_id", workspaceId)
                .eq("invitee_user_id", inviteeUserId)
                .eq("invitation_type", INVITATION_TARGETED)
                .eq("status", INVITE_PENDING)
                .ne("id", retainedInvitationId)
                .set("status", INVITE_REVOKED));
    }

    private WorkspaceInvitationVO createLinkInvitation(String workspaceId, String role) {
        ensureWorkspaceHasCapacityForInvite(workspaceId);
        String token = generateInvitationToken();
        WorkspaceInvitationEntity invitation = new WorkspaceInvitationEntity();
        invitation.setId(uuid());
        invitation.setWorkspaceId(workspaceId);
        invitation.setInvitationType(INVITATION_LINK);
        invitation.setRole(role);
        invitation.setTokenHash(hashInvitationToken(token));
        invitation.setStatus(INVITE_PENDING);
        invitation.setExpiresAt(linkInvitationExpiry());
        invitation.setCreator(SecurityUser.getUserId());
        invitationDao.insert(invitation);
        return toInvitationCreationVO(invitation, token);
    }

    @Override
    public WorkspaceInvitationVO previewInvitation(String code) {
        OpsAccess.requireUser();
        String normalizedCode = normalizeInvitationCode(code);
        WorkspaceInvitationEntity invitation = findInvitation(normalizedCode);
        if (invitation == null || !INVITE_PENDING.equals(invitation.getStatus())
                || invitation.getExpiresAt() == null || !invitation.getExpiresAt().after(new Date())) {
            throw new RenException(INVITATION_INVALID);
        }
        WorkspaceEntity workspace = workspaceDao.selectById(invitation.getWorkspaceId());
        if (workspace == null || !Integer.valueOf(ACTIVE).equals(workspace.getStatus())) {
            throw new RenException(INVITATION_INVALID);
        }
        requireActiveInvitationCreator(invitation);
        if (!isLinkInvitation(invitation)) {
            requireTargetedInvitee(invitation, SecurityUser.getUserId());
        }
        return toInvitationPreviewVO(invitation, workspace);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkspaceVO acceptInvitation(String code) {
        OpsAccess.requireUser();
        String normalizedCode = normalizeInvitationCode(code);
        Long userId = SecurityUser.getUserId();
        WorkspaceInvitationEntity candidate = findInvitation(normalizedCode);
        if (candidate == null || StringUtils.isBlank(candidate.getWorkspaceId())) {
            throw new RenException(INVITATION_INVALID);
        }
        TreeSet<Long> userIdsToLock = new TreeSet<>();
        userIdsToLock.add(userId);
        if (candidate.getCreator() != null) {
            userIdsToLock.add(candidate.getCreator());
        }
        LinkedHashMap<Long, SysUserEntity> lockedUsers = lockUsersForUpdate(userIdsToLock);
        SysUserEntity acceptingUser = lockedUsers.get(userId);
        if (acceptingUser == null || !Integer.valueOf(ACTIVE).equals(acceptingUser.getStatus())) {
            throw new RenException(ErrorCode.NO_PERMISSION);
        }
        WorkspaceEntity workspace = workspaceDao.lockByIdForInvitation(candidate.getWorkspaceId());
        if (workspace == null || !Integer.valueOf(ACTIVE).equals(workspace.getStatus())) {
            throw new RenException(INVITATION_INVALID);
        }
        WorkspaceInvitationEntity invitation = invitationDao.selectByIdForUpdate(candidate.getId());
        if (invitation == null || !matchesInvitationCode(invitation, normalizedCode)) {
            throw new RenException(INVITATION_INVALID);
        }
        boolean linkInvitation = isLinkInvitation(invitation);
        WorkspaceMemberEntity member = getMemberForUpdate(invitation.getWorkspaceId(), userId);
        if (linkInvitation && INVITE_ACCEPTED.equals(invitation.getStatus())
                && userId.equals(invitation.getAcceptedByUserId())
                && member != null && Integer.valueOf(ACTIVE).equals(member.getStatus())) {
            return toVO(workspace, member);
        }
        if (!INVITE_PENDING.equals(invitation.getStatus()) || invitation.getExpiresAt() == null
                || !invitation.getExpiresAt().after(new Date())) {
            throw new RenException(INVITATION_INVALID);
        }
        requireActiveInvitationCreatorForUpdate(invitation, lockedUsers.get(invitation.getCreator()));
        if (linkInvitation) {
            if (member != null && Integer.valueOf(ACTIVE).equals(member.getStatus())) {
                return toVO(workspace, member);
            }
        } else {
            requireTargetedInvitee(invitation, userId);
        }
        if (member == null || !Integer.valueOf(ACTIVE).equals(member.getStatus())) {
            ensureWorkspaceHasCapacityForAccept(invitation.getWorkspaceId());
            member = activateOrCreateMember(member, invitation.getWorkspaceId(), userId,
                    invitation.getRole(), invitation.getCreator());
        }
        if (linkInvitation) {
            invitation.setInviteeUserId(userId);
            invitation.setInviteeUsername(acceptingUser.getUsername());
        }
        invitation.setAcceptedByUserId(userId);
        invitation.setStatus(INVITE_ACCEPTED);
        invitation.setAcceptedAt(new Date());
        invitationDao.updateById(invitation);
        invitationDao.update(null, new UpdateWrapper<WorkspaceInvitationEntity>()
                .eq("workspace_id", invitation.getWorkspaceId())
                .eq("invitee_user_id", userId)
                .eq("invitation_type", INVITATION_TARGETED)
                .eq("status", INVITE_PENDING)
                .ne("id", invitation.getId())
                .set("status", INVITE_REVOKED));
        return toVO(workspace, member);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revokeInvitation(String workspaceId, String invitationId) {
        requireWrite(workspaceId);
        WorkspaceEntity workspace = workspaceDao.lockByIdForInvitation(workspaceId);
        requireWriteForUpdate(workspaceId);
        if (workspace == null || !Integer.valueOf(ACTIVE).equals(workspace.getStatus())) {
            throw new RenException("工作空间不存在或已停用");
        }
        invitationDao.update(null, new UpdateWrapper<WorkspaceInvitationEntity>()
                .eq("id", invitationId)
                .eq("workspace_id", workspaceId)
                .eq("status", INVITE_PENDING)
                .set("status", INVITE_REVOKED));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeMember(String workspaceId, Long userId) {
        requireWrite(workspaceId);
        WorkspaceEntity workspace = workspaceDao.lockByIdForInvitation(workspaceId);
        // Keep creator revocation serialized with invitation creation and acceptance.
        requireWriteForUpdate(workspaceId);
        requireNotFixedOwner(workspace, userId);
        if (SecurityUser.getUserId() != null && SecurityUser.getUserId().equals(userId)) {
            throw new RenException("不能移除自己，请使用退出工作空间");
        }
        WorkspaceMemberEntity member = getActiveMemberForUpdate(workspaceId, userId);
        if (member == null) {
            revokePendingInvitationsCreatedBy(workspaceId, userId);
            return;
        }
        if (ROLE_OWNER.equals(member.getRole())) {
            requireOwnerForUpdate(workspaceId);
        }
        if (ROLE_OWNER.equals(member.getRole()) && countOwnersForUpdate(workspaceId) <= 1) {
            throw new RenException("不能移除最后一个所有者");
        }
        disableMember(workspaceId, userId);
        revokePendingInvitationsCreatedBy(workspaceId, userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void leaveWorkspace(String workspaceId) {
        OpsAccess.requireUser();
        Long userId = SecurityUser.getUserId();
        WorkspaceEntity workspace = workspaceDao.lockByIdForInvitation(workspaceId);
        requireNotFixedOwner(workspace, userId);
        WorkspaceMemberEntity member = getActiveMemberForUpdate(workspaceId, userId);
        if (member == null) {
            throw new RenException(ErrorCode.NO_PERMISSION);
        }
        if (ROLE_OWNER.equals(member.getRole())) {
            throw new RenException("所有者不能退出工作空间");
        }
        disableMember(workspaceId, userId);
        revokePendingInvitationsCreatedBy(workspaceId, userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateMemberRole(String workspaceId, Long userId, WorkspaceRoleUpdateDTO dto) {
        requireOwner(workspaceId);
        WorkspaceEntity workspace = workspaceDao.lockByIdForInvitation(workspaceId);
        requireOwnerForUpdate(workspaceId);
        requireNotFixedOwner(workspace, userId);
        if (SecurityUser.getUserId() != null && SecurityUser.getUserId().equals(userId)) {
            throw new RenException("不能修改自己的工作空间角色");
        }
        String role = normalizeManagedMemberRole(dto.getRole());
        WorkspaceMemberEntity member = getActiveMemberForUpdate(workspaceId, userId);
        if (member == null) {
            throw new RenException("成员不存在");
        }
        if (ROLE_OWNER.equals(member.getRole()) && !ROLE_OWNER.equals(role)
                && countOwnersForUpdate(workspaceId) <= 1) {
            throw new RenException("不能移除最后一个所有者");
        }
        member.setRole(role);
        memberDao.updateById(member);
        if (!canCreateInvitations(role)) {
            revokePendingInvitationsCreatedBy(workspaceId, userId);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void prepareUserDeletion(Long userId) {
        if (sysUserDao.selectByIdForUpdate(userId) == null) {
            return;
        }
        if (!workspaceDao.selectOwnedByUserForUpdate(userId).isEmpty()) {
            throw new RenException("工作空间固定负责人不能注销");
        }
        List<WorkspaceMemberEntity> memberships = memberDao.selectList(new QueryWrapper<WorkspaceMemberEntity>()
                .eq("user_id", userId)
                .eq("status", ACTIVE));
        Set<String> workspaceIds = new TreeSet<>();
        memberships.stream()
                .map(WorkspaceMemberEntity::getWorkspaceId)
                .filter(StringUtils::isNotBlank)
                .forEach(workspaceIds::add);
        invitationDao.selectPendingWorkspaceIdsRelatedToUser(userId).stream()
                .filter(StringUtils::isNotBlank)
                .forEach(workspaceIds::add);
        LinkedHashMap<String, WorkspaceEntity> lockedWorkspaces = new LinkedHashMap<>();
        workspaceIds.forEach(workspaceId -> lockedWorkspaces.put(
                workspaceId, workspaceDao.lockByIdForInvitation(workspaceId)));
        // This is an explicit current read. The user-row lock prevents invitation acceptance
        // from adding another membership between the discovery query and this re-read.
        memberships = memberDao.selectActiveByUserForUpdate(userId);
        for (WorkspaceMemberEntity member : memberships) {
            WorkspaceEntity workspace = lockedWorkspaces.get(member.getWorkspaceId());
            if (workspace == null) {
                throw new RenException("工作空间成员关系已变化，请重试");
            }
            requireNotFixedOwner(workspace, userId);
            if (ROLE_OWNER.equals(member.getRole()) && countOwnersForUpdate(member.getWorkspaceId()) <= 1) {
                throw new RenException("工作空间所有者不能注销");
            }
        }
        lockedWorkspaces.keySet().forEach(workspaceId -> {
            revokePendingInvitationsCreatedBy(workspaceId, userId);
            revokePendingTargetedInvitationsForInvitee(workspaceId, userId);
        });
        memberDao.update(null, new UpdateWrapper<WorkspaceMemberEntity>()
                .eq("user_id", userId)
                .set("status", 0));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void prepareUsersDisabled(List<Long> userIds) {
        LinkedHashMap<Long, SysUserEntity> lockedUsers = lockUsersForUpdate(userIds);
        LinkedHashMap<Long, Set<String>> workspacesByCreator = new LinkedHashMap<>();
        TreeSet<String> workspaceIds = new TreeSet<>();
        lockedUsers.keySet().forEach(userId -> {
            TreeSet<String> creatorWorkspaceIds = invitationDao
                    .selectPendingWorkspaceIdsCreatedByUser(userId)
                    .stream()
                    .filter(StringUtils::isNotBlank)
                    .collect(Collectors.toCollection(TreeSet::new));
            workspacesByCreator.put(userId, creatorWorkspaceIds);
            workspaceIds.addAll(creatorWorkspaceIds);
        });
        workspaceIds.forEach(workspaceDao::lockByIdForInvitation);
        workspacesByCreator.forEach((creatorUserId, creatorWorkspaceIds) ->
                creatorWorkspaceIds.forEach(workspaceId ->
                        revokePendingInvitationsCreatedBy(workspaceId, creatorUserId)));
    }

    private void requireNotFixedOwner(WorkspaceEntity workspace, Long userId) {
        if (workspace != null && userId != null && userId.equals(workspace.getOwnerUserId())) {
            throw new RenException("固定负责人不能被移除、降级、退出或注销");
        }
    }

    @Override
    public WorkspaceMemberEntity getActiveMember(String workspaceId, Long userId) {
        WorkspaceMemberEntity member = getMember(workspaceId, userId);
        return member != null && Integer.valueOf(ACTIVE).equals(member.getStatus()) ? member : null;
    }

    private WorkspaceMemberEntity getMember(String workspaceId, Long userId) {
        if (StringUtils.isBlank(workspaceId) || userId == null) {
            return null;
        }
        return memberDao.selectOne(new QueryWrapper<WorkspaceMemberEntity>()
                .eq("workspace_id", workspaceId)
                .eq("user_id", userId)
                .last("LIMIT 1"));
    }

    private WorkspaceMemberEntity getMemberForUpdate(String workspaceId, Long userId) {
        if (StringUtils.isBlank(workspaceId) || userId == null) {
            return null;
        }
        return memberDao.selectByWorkspaceAndUserForUpdate(workspaceId, userId);
    }

    private WorkspaceMemberEntity getActiveMemberForUpdate(String workspaceId, Long userId) {
        WorkspaceMemberEntity member = getMemberForUpdate(workspaceId, userId);
        return member != null && Integer.valueOf(ACTIVE).equals(member.getStatus()) ? member : null;
    }

    @Override
    public boolean canRead(String workspaceId, Long userId) {
        if (!OpsAccess.isUser() || !java.util.Objects.equals(userId, SecurityUser.getUserId())) return false;
        return getActiveMember(workspaceId, userId) != null;
    }

    @Override
    public boolean canWrite(String workspaceId, Long userId) {
        if (!OpsAccess.isUser() || !java.util.Objects.equals(userId, SecurityUser.getUserId())) return false;
        WorkspaceMemberEntity member = getActiveMember(workspaceId, userId);
        return member != null && (ROLE_OWNER.equals(member.getRole()) || ROLE_ADMIN.equals(member.getRole()));
    }

    @Override
    public void requireRead(String workspaceId) {
        if (!canRead(workspaceId, SecurityUser.getUserId())) {
            throw new RenException(ErrorCode.NO_PERMISSION);
        }
    }

    @Override
    public void requireWrite(String workspaceId) {
        if (!canWrite(workspaceId, SecurityUser.getUserId())) {
            throw new RenException(ErrorCode.NO_PERMISSION);
        }
    }

    private void requireWriteForUpdate(String workspaceId) {
        OpsAccess.requireUser();
        WorkspaceMemberEntity member = getActiveMemberForUpdate(workspaceId, SecurityUser.getUserId());
        if (member == null || (!ROLE_OWNER.equals(member.getRole()) && !ROLE_ADMIN.equals(member.getRole()))) {
            throw new RenException(ErrorCode.NO_PERMISSION);
        }
    }

    @Override
    public void requireOwner(String workspaceId) {
        OpsAccess.requireUser();
        WorkspaceMemberEntity member = getActiveMember(workspaceId, SecurityUser.getUserId());
        if (member == null || !ROLE_OWNER.equals(member.getRole())) {
            throw new RenException(ErrorCode.NO_PERMISSION);
        }
    }

    private void requireOwnerForUpdate(String workspaceId) {
        OpsAccess.requireUser();
        WorkspaceMemberEntity member = getActiveMemberForUpdate(workspaceId, SecurityUser.getUserId());
        if (member == null || !ROLE_OWNER.equals(member.getRole())) {
            throw new RenException(ErrorCode.NO_PERMISSION);
        }
    }

    private WorkspaceMemberEntity createMember(String workspaceId, Long userId, String role, Long creator) {
        WorkspaceMemberEntity member = new WorkspaceMemberEntity();
        member.setId(uuid());
        member.setWorkspaceId(workspaceId);
        member.setUserId(userId);
        member.setRole(role);
        member.setStatus(ACTIVE);
        member.setCreator(creator);
        member.setUpdater(creator);
        member.setCreatedAt(new Date());
        member.setUpdatedAt(new Date());
        memberDao.insert(member);
        return member;
    }

    private WorkspaceMemberEntity activateOrCreateMember(WorkspaceMemberEntity member, String workspaceId,
            Long userId, String role, Long updater) {
        if (member == null) {
            return createMember(workspaceId, userId, role, updater);
        }
        member.setRole(role);
        member.setStatus(ACTIVE);
        member.setUpdater(updater);
        member.setUpdatedAt(new Date());
        memberDao.updateById(member);
        return member;
    }

    private void disableMember(String workspaceId, Long userId) {
        memberDao.update(null, new UpdateWrapper<WorkspaceMemberEntity>()
                .eq("workspace_id", workspaceId)
                .eq("user_id", userId)
                .set("status", 0));
    }

    private void revokePendingInvitationsCreatedBy(String workspaceId, Long creatorUserId) {
        if (StringUtils.isBlank(workspaceId) || creatorUserId == null) {
            return;
        }
        invitationDao.update(null, new UpdateWrapper<WorkspaceInvitationEntity>()
                .eq("workspace_id", workspaceId)
                .eq("creator", creatorUserId)
                .eq("status", INVITE_PENDING)
                .set("status", INVITE_REVOKED));
    }

    private void revokePendingTargetedInvitationsForInvitee(String workspaceId, Long inviteeUserId) {
        if (StringUtils.isBlank(workspaceId) || inviteeUserId == null) {
            return;
        }
        invitationDao.update(null, new UpdateWrapper<WorkspaceInvitationEntity>()
                .eq("workspace_id", workspaceId)
                .eq("invitee_user_id", inviteeUserId)
                .eq("invitation_type", INVITATION_TARGETED)
                .eq("status", INVITE_PENDING)
                .set("status", INVITE_REVOKED));
    }

    private void ensureOwnerMember(WorkspaceEntity workspace, Long userId) {
        WorkspaceMemberEntity member = getMember(workspace.getId(), userId);
        if (member == null || !Integer.valueOf(ACTIVE).equals(member.getStatus())
                || !ROLE_OWNER.equals(member.getRole())) {
            activateOrCreateMember(member, workspace.getId(), userId, ROLE_OWNER, userId);
        }
    }

    private WorkspaceVO toVO(WorkspaceEntity workspace, WorkspaceMemberEntity member) {
        WorkspaceVO vo = new WorkspaceVO();
        if (workspace == null) {
            return vo;
        }
        vo.setId(workspace.getId());
        vo.setName(workspace.getName());
        vo.setOwnerUserId(workspace.getOwnerUserId());
        vo.setDefaultWorkspace(workspace.getDefaultWorkspace());
        vo.setCreatedAt(workspace.getCreatedAt());
        vo.setCurrentUserRole(member == null ? null : member.getRole());
        vo.setCanWrite(member != null && (ROLE_OWNER.equals(member.getRole()) || ROLE_ADMIN.equals(member.getRole())));
        return vo;
    }

    private WorkspaceMemberVO toMemberVO(WorkspaceMemberEntity member) {
        WorkspaceMemberVO vo = new WorkspaceMemberVO();
        vo.setUserId(member.getUserId());
        SysUserEntity user = sysUserDao.selectById(member.getUserId());
        vo.setUsername(user == null ? String.valueOf(member.getUserId()) : user.getUsername());
        vo.setRole(member.getRole());
        vo.setStatus(member.getStatus());
        vo.setJoinedAt(member.getCreatedAt());
        return vo;
    }

    private WorkspaceInvitationVO toInvitationVO(WorkspaceInvitationEntity invitation) {
        WorkspaceInvitationVO vo = new WorkspaceInvitationVO();
        WorkspaceEntity workspace = workspaceDao.selectById(invitation.getWorkspaceId());
        vo.setId(invitation.getId());
        vo.setWorkspaceId(invitation.getWorkspaceId());
        vo.setWorkspaceName(workspace == null ? "" : workspace.getName());
        vo.setInviteeUsername(invitation.getInviteeUsername());
        vo.setInvitationType(invitationType(invitation));
        vo.setLinkInvitation(isLinkInvitation(invitation));
        vo.setRole(invitation.getRole());
        vo.setStatus(invitation.getStatus());
        vo.setExpiresAt(invitation.getExpiresAt());
        return vo;
    }

    private WorkspaceInvitationVO toInvitationCreationVO(WorkspaceInvitationEntity invitation, String token) {
        WorkspaceInvitationVO vo = toInvitationVO(invitation);
        vo.setCode(token);
        return vo;
    }

    private WorkspaceInvitationVO toInvitationPreviewVO(WorkspaceInvitationEntity invitation,
            WorkspaceEntity workspace) {
        WorkspaceInvitationVO vo = new WorkspaceInvitationVO();
        vo.setWorkspaceName(workspace.getName());
        vo.setRole(invitation.getRole());
        vo.setExpiresAt(invitation.getExpiresAt());
        return vo;
    }

    private int countOwnersForUpdate(String workspaceId) {
        return Math.toIntExact(activeMembersForUpdate(workspaceId).stream()
                .filter(member -> ROLE_OWNER.equals(member.getRole()))
                .count());
    }

    private List<WorkspaceMemberEntity> activeMembersForUpdate(String workspaceId) {
        return memberDao.selectActiveByWorkspaceForUpdate(workspaceId);
    }

    private int countPendingInvitationsForUpdate(String workspaceId) {
        return effectivePendingInvitationsForUpdate(workspaceId).size();
    }

    private List<WorkspaceInvitationEntity> effectivePendingInvitations(String workspaceId) {
        Set<Long> activeUserIds = activeMemberUserIds(workspaceId);
        return new java.util.ArrayList<>(pendingInvitations(workspaceId).stream()
                .filter(invitation -> invitation.getInviteeUserId() == null
                        || !activeUserIds.contains(invitation.getInviteeUserId()))
                .collect(Collectors.toMap(
                        invitation -> invitation.getInviteeUserId() == null
                                ? "invitation:" + invitation.getId()
                                : "user:" + invitation.getInviteeUserId(),
                        invitation -> invitation,
                        (older, newer) -> newer,
                        LinkedHashMap::new))
                .values());
    }

    private List<WorkspaceInvitationEntity> effectivePendingInvitationsForUpdate(String workspaceId) {
        Set<Long> activeUserIds = activeMembersForUpdate(workspaceId).stream()
                .map(WorkspaceMemberEntity::getUserId)
                .collect(Collectors.toSet());
        return new java.util.ArrayList<>(invitationDao.selectPendingByWorkspaceForUpdate(workspaceId, new Date())
                .stream()
                .filter(invitation -> invitation.getInviteeUserId() == null
                        || !activeUserIds.contains(invitation.getInviteeUserId()))
                .collect(Collectors.toMap(
                        invitation -> invitation.getInviteeUserId() == null
                                ? "invitation:" + invitation.getId()
                                : "user:" + invitation.getInviteeUserId(),
                        invitation -> invitation,
                        (older, newer) -> newer,
                        LinkedHashMap::new))
                .values());
    }

    private List<WorkspaceInvitationEntity> pendingInvitations(String workspaceId) {
        return invitationDao.selectList(new QueryWrapper<WorkspaceInvitationEntity>()
                .eq("workspace_id", workspaceId)
                .eq("status", INVITE_PENDING)
                .gt("expires_at", new Date())
                .orderByAsc("created_at"));
    }

    private Set<Long> activeMemberUserIds(String workspaceId) {
        return memberDao.selectList(new QueryWrapper<WorkspaceMemberEntity>()
                .eq("workspace_id", workspaceId)
                .eq("status", ACTIVE))
                .stream()
                .map(WorkspaceMemberEntity::getUserId)
                .collect(Collectors.toSet());
    }

    private Date targetedInvitationExpiry() {
        return new Date(System.currentTimeMillis() + TARGETED_INVITATION_TTL_MILLIS);
    }

    private Date linkInvitationExpiry() {
        return new Date(System.currentTimeMillis() + LINK_INVITATION_TTL_MILLIS);
    }

    private void ensureWorkspaceHasCapacityForInvite(String workspaceId) {
        if (activeMembersForUpdate(workspaceId).size()
                + countPendingInvitationsForUpdate(workspaceId) >= MAX_WORKSPACE_MEMBERS) {
            throw new RenException("工作空间成员上限为5人");
        }
    }

    private void ensureWorkspaceHasCapacityForAccept(String workspaceId) {
        if (activeMembersForUpdate(workspaceId).size() >= MAX_WORKSPACE_MEMBERS) {
            throw new RenException("工作空间成员上限为5人");
        }
    }

    private String normalizeInviteRole(String role) {
        String normalized = StringUtils.defaultIfBlank(role, ROLE_MEMBER).toUpperCase(Locale.ROOT);
        if (ROLE_OWNER.equals(normalized)) {
            throw new RenException("不能邀请为所有者");
        }
        return normalizeMemberRole(normalized);
    }

    private String normalizeMemberRole(String role) {
        String normalized = StringUtils.defaultIfBlank(role, ROLE_MEMBER).toUpperCase(Locale.ROOT);
        if (!ROLE_OWNER.equals(normalized) && !ROLE_ADMIN.equals(normalized) && !ROLE_MEMBER.equals(normalized)) {
            throw new RenException("无效的成员角色");
        }
        return normalized;
    }

    private String normalizeManagedMemberRole(String role) {
        String normalized = normalizeMemberRole(role);
        if (ROLE_OWNER.equals(normalized)) {
            throw new RenException("不能授予所有者角色");
        }
        return normalized;
    }

    private boolean canCreateInvitations(String role) {
        return ROLE_OWNER.equals(role) || ROLE_ADMIN.equals(role);
    }

    private WorkspaceInvitationEntity findInvitation(String normalizedCode) {
        String tokenHash = hashInvitationToken(normalizedCode);
        WorkspaceInvitationEntity invitation = invitationDao.selectOne(
                new QueryWrapper<WorkspaceInvitationEntity>()
                        .eq("token_hash", tokenHash)
                        .last("LIMIT 1"));
        return invitation != null && matchesInvitationCode(invitation, normalizedCode) ? invitation : null;
    }

    private boolean matchesInvitationCode(WorkspaceInvitationEntity invitation, String normalizedCode) {
        return StringUtils.isNotBlank(invitation.getTokenHash())
                && MessageDigest.isEqual(
                        invitation.getTokenHash().getBytes(StandardCharsets.US_ASCII),
                        hashInvitationToken(normalizedCode).getBytes(StandardCharsets.US_ASCII));
    }

    private String normalizeInvitationCode(String code) {
        String normalized = StringUtils.trim(code);
        if (normalized == null || !INVITATION_CODE_PATTERN.matcher(normalized).matches()) {
            throw new RenException(INVITATION_INVALID);
        }
        return normalized;
    }

    private String invitationType(WorkspaceInvitationEntity invitation) {
        return INVITATION_LINK.equals(invitation.getInvitationType())
                ? INVITATION_LINK
                : INVITATION_TARGETED;
    }

    private boolean isLinkInvitation(WorkspaceInvitationEntity invitation) {
        return INVITATION_LINK.equals(invitationType(invitation));
    }

    private void requireActiveInvitationCreator(WorkspaceInvitationEntity invitation) {
        Long creatorUserId = invitation.getCreator();
        if (creatorUserId == null) {
            throw new RenException(INVITATION_INVALID);
        }
        WorkspaceMemberEntity creator = getActiveMember(invitation.getWorkspaceId(), creatorUserId);
        if (creator == null || (!ROLE_OWNER.equals(creator.getRole()) && !ROLE_ADMIN.equals(creator.getRole()))) {
            throw new RenException(INVITATION_INVALID);
        }
        SysUserEntity creatorUser = sysUserDao.selectById(creatorUserId);
        if (creatorUser == null || !Integer.valueOf(ACTIVE).equals(creatorUser.getStatus())) {
            throw new RenException(INVITATION_INVALID);
        }
    }

    private void requireActiveInvitationCreatorForUpdate(WorkspaceInvitationEntity invitation,
            SysUserEntity lockedCreatorUser) {
        Long creatorUserId = invitation.getCreator();
        if (creatorUserId == null || lockedCreatorUser == null
                || !creatorUserId.equals(lockedCreatorUser.getId())
                || !Integer.valueOf(ACTIVE).equals(lockedCreatorUser.getStatus())) {
            throw new RenException(INVITATION_INVALID);
        }
        WorkspaceMemberEntity creator = getActiveMemberForUpdate(
                invitation.getWorkspaceId(), creatorUserId);
        if (creator == null || (!ROLE_OWNER.equals(creator.getRole()) && !ROLE_ADMIN.equals(creator.getRole()))) {
            throw new RenException(INVITATION_INVALID);
        }
    }

    private LinkedHashMap<Long, SysUserEntity> lockUsersForUpdate(Iterable<Long> userIds) {
        TreeSet<Long> sortedUserIds = new TreeSet<>();
        if (userIds != null) {
            userIds.forEach(userId -> {
                if (userId != null) {
                    sortedUserIds.add(userId);
                }
            });
        }
        LinkedHashMap<Long, SysUserEntity> lockedUsers = new LinkedHashMap<>();
        sortedUserIds.forEach(userId -> {
            SysUserEntity user = sysUserDao.selectByIdForUpdate(userId);
            if (user != null) {
                lockedUsers.put(userId, user);
            }
        });
        return lockedUsers;
    }

    private void requireActiveUser(SysUserEntity user) {
        if (user == null || !Integer.valueOf(ACTIVE).equals(user.getStatus())) {
            throw new RenException(ErrorCode.UNAUTHORIZED);
        }
    }

    private void requireTargetedInvitee(WorkspaceInvitationEntity invitation, Long userId) {
        if (invitation.getInviteeUserId() != null) {
            if (!invitation.getInviteeUserId().equals(userId)) {
                throw new RenException(ErrorCode.NO_PERMISSION);
            }
            return;
        }
        SysUserEntity user = sysUserDao.selectById(userId);
        if (user == null || StringUtils.isBlank(invitation.getInviteeUsername())
                || !invitation.getInviteeUsername().equals(user.getUsername())) {
            throw new RenException(ErrorCode.NO_PERMISSION);
        }
    }

    private String getRequestedWorkspaceId() {
        if (HttpContextUtils.getHttpServletRequest() == null) {
            return null;
        }
        return HttpContextUtils.getHttpServletRequest().getHeader(HEADER_WORKSPACE_ID);
    }



    private String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String generateInvitationToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashInvitationToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}

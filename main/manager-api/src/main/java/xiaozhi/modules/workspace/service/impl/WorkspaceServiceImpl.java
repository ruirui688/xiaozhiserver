package xiaozhi.modules.workspace.service.impl;

import java.security.SecureRandom;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
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
    private static final int MAX_WORKSPACE_MEMBERS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final WorkspaceDao workspaceDao;
    private final WorkspaceMemberDao memberDao;
    private final WorkspaceInvitationDao invitationDao;
    private final SysUserDao sysUserDao;

    @Override
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
    public WorkspaceVO getCurrentWorkspace() {
        WorkspaceEntity workspace = getCurrentWorkspaceEntity();
        return toVO(workspace, getActiveMember(workspace.getId(), SecurityUser.getUserId()));
    }

    @Override
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
        WorkspaceEntity existing = getDefaultWorkspace(userId);
        if (existing != null) {
            // Existing memberships are authoritative; never restore a removed owner.
            return existing;
        }
        SysUserEntity user = sysUserDao.selectById(userId);
        String username = user == null ? String.valueOf(userId) : user.getUsername();
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
        WorkspaceEntity target = workspaceDao.selectById(workspaceId);
        if (target == null || !Integer.valueOf(ACTIVE).equals(target.getStatus())) throw new RenException("工作空间不存在或已停用");
        String role = normalizeInviteRole(dto.getRole());
        String username = dto.getUsername().trim();
        SysUserEntity invitee = sysUserDao.selectOne(new QueryWrapper<SysUserEntity>()
                .eq("username", username)
                .last("LIMIT 1"));
        if (invitee == null) {
            throw new RenException("被邀请用户不存在");
        }
        if (getActiveMember(workspaceId, invitee.getId()) != null) {
            throw new RenException("该用户已在工作空间中");
        }
        WorkspaceInvitationEntity existing = invitationDao.selectOne(
                new QueryWrapper<WorkspaceInvitationEntity>()
                        .eq("workspace_id", workspaceId)
                        .eq("invitee_user_id", invitee.getId())
                        .eq("status", INVITE_PENDING)
                        .gt("expires_at", new Date())
                        .orderByDesc("created_at")
                        .last("LIMIT 1"));
        if (existing != null) {
            existing.setInviteeUsername(username);
            existing.setRole(role);
            existing.setExpiresAt(invitationExpiry());
            invitationDao.updateById(existing);
            return toInvitationVO(existing);
        }
        ensureWorkspaceHasCapacityForInvite(workspaceId);
        WorkspaceInvitationEntity invitation = new WorkspaceInvitationEntity();
        invitation.setId(uuid());
        invitation.setWorkspaceId(workspaceId);
        invitation.setInviteeUsername(username);
        invitation.setInviteeUserId(invitee.getId());
        invitation.setRole(role);
        invitation.setCode(generateCode());
        invitation.setStatus(INVITE_PENDING);
        invitation.setExpiresAt(invitationExpiry());
        invitationDao.insert(invitation);
        return toInvitationVO(invitation);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkspaceVO acceptInvitation(String code) {
        OpsAccess.requireUser();
        Long userId = SecurityUser.getUserId();
        WorkspaceInvitationEntity invitation = invitationDao.selectOne(new QueryWrapper<WorkspaceInvitationEntity>()
                .eq("code", code)
                .eq("status", INVITE_PENDING)
                .last("LIMIT 1"));
        if (invitation == null || invitation.getExpiresAt().before(new Date())) {
            throw new RenException("邀请不存在或已过期");
        }
        WorkspaceEntity workspace = workspaceDao.selectById(invitation.getWorkspaceId());
        if (workspace == null || !Integer.valueOf(ACTIVE).equals(workspace.getStatus())) {
            throw new RenException("邀请仅适用于启用的工作空间");
        }
        SysUserEntity user = sysUserDao.selectById(userId);
        if (user == null || !user.getUsername().equals(invitation.getInviteeUsername())) {
            throw new RenException(ErrorCode.NO_PERMISSION);
        }
        WorkspaceMemberEntity member = getMember(invitation.getWorkspaceId(), userId);
        if (member == null || !Integer.valueOf(ACTIVE).equals(member.getStatus())) {
            ensureWorkspaceHasCapacityForAccept(invitation.getWorkspaceId());
            member = activateOrCreateMember(member, invitation.getWorkspaceId(), userId,
                    invitation.getRole(), invitation.getCreator());
        }
        invitation.setStatus(INVITE_ACCEPTED);
        invitation.setAcceptedAt(new Date());
        invitationDao.updateById(invitation);
        invitationDao.update(null, new UpdateWrapper<WorkspaceInvitationEntity>()
                .eq("workspace_id", invitation.getWorkspaceId())
                .eq("invitee_user_id", userId)
                .eq("status", INVITE_PENDING)
                .ne("id", invitation.getId())
                .set("status", INVITE_REVOKED));
        return toVO(workspaceDao.selectById(invitation.getWorkspaceId()), member);
    }

    @Override
    public void revokeInvitation(String workspaceId, String invitationId) {
        requireWrite(workspaceId);
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
        requireNotFixedOwner(workspaceId, userId);
        if (SecurityUser.getUserId() != null && SecurityUser.getUserId().equals(userId)) {
            throw new RenException("不能移除自己，请使用退出工作空间");
        }
        WorkspaceMemberEntity member = getActiveMember(workspaceId, userId);
        if (member == null) {
            return;
        }
        if (ROLE_OWNER.equals(member.getRole())) {
            requireOwner(workspaceId);
        }
        if (ROLE_OWNER.equals(member.getRole()) && countOwners(workspaceId) <= 1) {
            throw new RenException("不能移除最后一个所有者");
        }
        disableMember(workspaceId, userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void leaveWorkspace(String workspaceId) {
        OpsAccess.requireUser();
        Long userId = SecurityUser.getUserId();
        requireNotFixedOwner(workspaceId, userId);
        WorkspaceMemberEntity member = getActiveMember(workspaceId, userId);
        if (member == null) {
            throw new RenException(ErrorCode.NO_PERMISSION);
        }
        if (ROLE_OWNER.equals(member.getRole())) {
            throw new RenException("所有者不能退出工作空间");
        }
        disableMember(workspaceId, userId);
    }

    @Override
    public void updateMemberRole(String workspaceId, Long userId, WorkspaceRoleUpdateDTO dto) {
        requireOwner(workspaceId);
        requireNotFixedOwner(workspaceId, userId);
        if (SecurityUser.getUserId() != null && SecurityUser.getUserId().equals(userId)) {
            throw new RenException("不能修改自己的工作空间角色");
        }
        String role = normalizeMemberRole(dto.getRole());
        WorkspaceMemberEntity member = getActiveMember(workspaceId, userId);
        if (member == null) {
            throw new RenException("成员不存在");
        }
        if (ROLE_OWNER.equals(member.getRole()) && !ROLE_OWNER.equals(role) && countOwners(workspaceId) <= 1) {
            throw new RenException("不能移除最后一个所有者");
        }
        member.setRole(role);
        memberDao.updateById(member);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void prepareUserDeletion(Long userId) {
        if (workspaceDao.selectCount(new QueryWrapper<WorkspaceEntity>().eq("owner_user_id", userId)) > 0) {
            throw new RenException("工作空间固定负责人不能注销");
        }
        List<WorkspaceMemberEntity> memberships = memberDao.selectList(new QueryWrapper<WorkspaceMemberEntity>()
                .eq("user_id", userId)
                .eq("status", ACTIVE));
        for (WorkspaceMemberEntity member : memberships) {
            requireNotFixedOwner(member.getWorkspaceId(), userId);
            if (ROLE_OWNER.equals(member.getRole()) && countOwners(member.getWorkspaceId()) <= 1) {
                throw new RenException("工作空间所有者不能注销");
            }
        }
        memberDao.update(null, new UpdateWrapper<WorkspaceMemberEntity>()
                .eq("user_id", userId)
                .set("status", 0));
    }

    private void requireNotFixedOwner(String workspaceId, Long userId) {
        WorkspaceEntity workspace = workspaceDao.selectById(workspaceId);
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

    @Override
    public void requireOwner(String workspaceId) {
        OpsAccess.requireUser();
        WorkspaceMemberEntity member = getActiveMember(workspaceId, SecurityUser.getUserId());
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
        vo.setCode(invitation.getCode());
        vo.setWorkspaceId(invitation.getWorkspaceId());
        vo.setWorkspaceName(workspace == null ? "" : workspace.getName());
        vo.setInviteeUsername(invitation.getInviteeUsername());
        vo.setRole(invitation.getRole());
        vo.setStatus(invitation.getStatus());
        vo.setExpiresAt(invitation.getExpiresAt());
        return vo;
    }

    private int countOwners(String workspaceId) {
        return Math.toIntExact(memberDao.selectCount(new QueryWrapper<WorkspaceMemberEntity>()
                .eq("workspace_id", workspaceId)
                .eq("role", ROLE_OWNER)
                .eq("status", ACTIVE)));
    }

    private int countActiveMembers(String workspaceId) {
        return Math.toIntExact(memberDao.selectCount(new QueryWrapper<WorkspaceMemberEntity>()
                .eq("workspace_id", workspaceId)
                .eq("status", ACTIVE)));
    }

    private int countPendingInvitations(String workspaceId) {
        return effectivePendingInvitations(workspaceId).size();
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

    private Date invitationExpiry() {
        return new Date(System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000);
    }

    private void ensureWorkspaceHasCapacityForInvite(String workspaceId) {
        if (countActiveMembers(workspaceId) + countPendingInvitations(workspaceId) >= MAX_WORKSPACE_MEMBERS) {
            throw new RenException("工作空间成员上限为5人");
        }
    }

    private void ensureWorkspaceHasCapacityForAccept(String workspaceId) {
        if (countActiveMembers(workspaceId) >= MAX_WORKSPACE_MEMBERS) {
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

    private String getRequestedWorkspaceId() {
        if (HttpContextUtils.getHttpServletRequest() == null) {
            return null;
        }
        return HttpContextUtils.getHttpServletRequest().getHeader(HEADER_WORKSPACE_ID);
    }



    private String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String generateCode() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}

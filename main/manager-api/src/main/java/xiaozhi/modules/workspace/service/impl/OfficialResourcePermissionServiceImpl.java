package xiaozhi.modules.workspace.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import lombok.AllArgsConstructor;
import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.modules.sys.dao.SysUserDao;
import xiaozhi.modules.sys.entity.SysUserEntity;
import xiaozhi.modules.workspace.dao.WorkspaceDao;
import xiaozhi.modules.workspace.dao.WorkspaceMemberDao;
import xiaozhi.modules.workspace.entity.WorkspaceEntity;
import xiaozhi.modules.workspace.entity.WorkspaceMemberEntity;
import xiaozhi.modules.workspace.service.OfficialResourcePermissionService;

@Service
@AllArgsConstructor
public class OfficialResourcePermissionServiceImpl implements OfficialResourcePermissionService {
    private static final int ACTIVE = 1;
    private static final int SUPER_ADMIN = 1;

    private final WorkspaceMemberDao memberDao;
    private final WorkspaceDao workspaceDao;
    private final SysUserDao userDao;

    @Override
    public boolean isOfficialMaintainer() {
        Long userId = SecurityUser.getUserId();
        if (userId == null) {
            return false;
        }
        if (isActiveSuperAdmin(userId)) {
            return true;
        }
        List<String> workspaceIds = memberDao.selectList(new LambdaQueryWrapper<WorkspaceMemberEntity>()
                        .eq(WorkspaceMemberEntity::getUserId, userId)
                        .eq(WorkspaceMemberEntity::getStatus, ACTIVE))
                .stream().map(WorkspaceMemberEntity::getWorkspaceId).distinct().toList();
        if (workspaceIds.isEmpty()) {
            return false;
        }
        List<Long> ownerIds = workspaceDao.selectList(new LambdaQueryWrapper<WorkspaceEntity>()
                        .in(WorkspaceEntity::getId, workspaceIds)
                        .eq(WorkspaceEntity::getStatus, ACTIVE))
                .stream().map(WorkspaceEntity::getOwnerUserId).distinct().toList();
        return !ownerIds.isEmpty() && userDao.selectCount(new LambdaQueryWrapper<SysUserEntity>()
                .in(SysUserEntity::getId, ownerIds)
                .eq(SysUserEntity::getSuperAdmin, SUPER_ADMIN)
                .eq(SysUserEntity::getStatus, ACTIVE)) > 0;
    }

    @Override
    public void requireOfficialMaintainer() {
        if (!isOfficialMaintainer()) {
            RenException exception = new RenException("仅超级管理员团队可以维护官方资源");
            exception.setCode(ErrorCode.NO_PERMISSION);
            throw exception;
        }
    }

    private boolean isActiveSuperAdmin(Long userId) {
        return userDao.selectCount(new LambdaQueryWrapper<SysUserEntity>()
                .eq(SysUserEntity::getId, userId)
                .eq(SysUserEntity::getSuperAdmin, SUPER_ADMIN)
                .eq(SysUserEntity::getStatus, ACTIVE)) > 0;
    }
}

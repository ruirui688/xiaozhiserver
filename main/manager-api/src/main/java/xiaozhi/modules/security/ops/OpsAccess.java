package xiaozhi.modules.security.ops;

import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.user.UserDetail;
import xiaozhi.modules.security.user.SecurityUser;

/** Exact action and resource matching. No wildcards, administrator shortcut or tenant-content grants. */
public final class OpsAccess {
    private OpsAccess() {}
    public static boolean isUser() { return isUser(SecurityUser.getUser()); }
    public static boolean isUser(UserDetail user) {
        return user != null && user.getId() != null && "USER".equals(user.getAudience());
    }
    public static void requireUser() { if (!isUser()) throw new RenException(ErrorCode.NO_PERMISSION); }
    public static UserDetail employee() {
        UserDetail user = SecurityUser.getUser();
        if (user == null || !"OPS".equals(user.getAudience()) || user.getEmployeeId() == null)
            throw new RenException(ErrorCode.NO_PERMISSION);
        return user;
    }
    public static boolean has(String permission) {
        UserDetail user = SecurityUser.getUser();
        return user != null && "OPS".equals(user.getAudience()) && user.getEmployeeId() != null
                && user.getGrants() != null && user.getGrants().stream().anyMatch(g -> g.permissions().contains(permission));
    }
    public static boolean has(String permission, String scopeType, String scopeId) {
        UserDetail user = SecurityUser.getUser();
        return has(permission) && user.getGrants().stream().anyMatch(g -> g.permissions().contains(permission)
                && g.scopeType().equals(scopeType) && g.scopeId().equals(scopeId));
    }
    public static void require(String permission, String scopeType, String scopeId) {
        if (!has(permission, scopeType, scopeId)) throw new RenException(ErrorCode.NO_PERMISSION);
    }
}

package xiaozhi.modules.security.ops;

import java.util.Set;
import xiaozhi.common.exception.RenException;

/** Generic parameter CRUD must never expose service credentials or redirect runtime destinations. */
public final class OpsSystemPolicy {
    private static final Set<String> ALLOWED=Set.of("server.allow_user_register","workspace.agent_max_count","workspace.agent_storage_limit_mb","system-web.menu");
    private OpsSystemPolicy() {}
    public static Set<String> allowedCodes(){return ALLOWED;}
    public static boolean allowed(String key){return key!=null&&ALLOWED.contains(key);}
    public static void require(String key){if(!allowed(key))throw new RenException("该参数不允许通过通用运营参数接口读取或修改");}
}

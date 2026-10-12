package xiaozhi.modules.security.ops;

import java.io.Serializable;
import java.util.Set;

public record OpsGrant(String roleCode, String scopeType, String scopeId, Set<String> permissions) implements Serializable {}

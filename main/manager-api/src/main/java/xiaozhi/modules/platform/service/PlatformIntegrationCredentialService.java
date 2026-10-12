package xiaozhi.modules.platform.service;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.security.CredentialCipher;
import xiaozhi.modules.model.dao.ModelConfigDao;

/** Java-only external KB connector credential. Never usable as a platform model credential. */
@Service @RequiredArgsConstructor
public class PlatformIntegrationCredentialService {
    private final ModelConfigDao models;
    private final JdbcTemplate jdbc;
    private final CredentialCipher cipher;
    public Map<String,Object> ragflow(String modelId,Map<String,Object> publicConfig) {
        var model=models.selectById(modelId);
        if(model==null||!"RAG".equalsIgnoreCase(model.getModelType())||!"ragflow".equals(model.getAdapterCode()))throw new RenException("EXTERNAL_KB_INTEGRATION_REQUIRED");
        var rows=jdbc.queryForList("SELECT secret_value FROM ai_model_config_secret WHERE model_id=? AND secret_key='api_key'",modelId);
        if(rows.size()!=1)throw new RenException("EXTERNAL_KB_CREDENTIAL_REQUIRED");
        String encrypted=rows.getFirst().get("secret_value").toString();
        if(!encrypted.startsWith("v1:"))throw new RenException("EXTERNAL_KB_CREDENTIAL_NOT_ENCRYPTED");
        Map<String,Object> runtime=new LinkedHashMap<>(publicConfig);
        runtime.put("api_key",cipher.decrypt("legacy-model:"+modelId+":api_key",encrypted));return runtime;
    }
}

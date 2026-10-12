package xiaozhi.modules.platform.service;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import xiaozhi.common.security.CredentialCipher;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.model.dao.ModelConfigDao;
import xiaozhi.modules.model.entity.ModelConfigEntity;
class PlatformIntegrationCredentialServiceTest {
    @Test void externalKbKeyStaysInJavaAndCannotReadAnyModelExecutionSecret() {
        var models=mock(ModelConfigDao.class);var jdbc=mock(JdbcTemplate.class);var cipher=new CredentialCipher("test-integration-master-key-32-chars");
        var model=new ModelConfigEntity();model.setModelType("LLM");model.setAdapterCode("openai");when(models.selectById("model")).thenReturn(model);
        var service=new PlatformIntegrationCredentialService(models,jdbc,cipher);assertThrows(RenException.class,()->service.ragflow("model",Map.of()));verifyNoInteractions(jdbc);
        model.setModelType("RAG");model.setAdapterCode("ragflow");
        when(jdbc.queryForList("SELECT secret_value FROM ai_model_config_secret WHERE model_id=? AND secret_key='api_key'","model")).thenReturn(List.of(Map.of("secret_value",cipher.encrypt("legacy-model:model:api_key","fake-integration-key"))));
        Map<String,Object> publicConfig=Map.of("api_key","PLATFORM_MANAGED","type","ragflow");
        assertEquals("fake-integration-key",service.ragflow("model",publicConfig).get("api_key"));assertEquals("PLATFORM_MANAGED",publicConfig.get("api_key"));
    }
}

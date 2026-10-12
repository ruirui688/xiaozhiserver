package xiaozhi.modules.security.ops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import xiaozhi.common.user.UserDetail;
import xiaozhi.modules.agent.dao.AgentTemplateDao;
import xiaozhi.modules.agent.entity.AgentTemplateEntity;
import xiaozhi.modules.security.user.SecurityUser;

class OpsTemplatePersistenceTest {
    private OpsTemplateService service;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private MockedStatic<SecurityUser> security;
    private static final List<String> REFERENCES = List.of("asrModelId", "vadModelId", "llmModelId", "slmModelId", "vllmModelId", "ttsModelId", "ttsVoiceId", "memModelId", "intentModelId");

    @BeforeEach void setup() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:template-" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLogImpl(org.apache.ibatis.logging.nologging.NoLoggingImpl.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source); factory.setConfiguration(configuration);
        var sessions = factory.getObject();
        sessions.getConfiguration().addMapper(AgentTemplateDao.class);
        // Use the real mapper columns with the historical migration's visual-model
        // default, so omitting a nullable field really does inject that reference.
        var columns = new ArrayList<String>(); columns.add("id VARCHAR(64) PRIMARY KEY");
        for (var field : TableInfoHelper.getTableInfo(AgentTemplateEntity.class).getFieldList()) {
            String type = field.getPropertyType() == Date.class ? "TIMESTAMP"
                : Number.class.isAssignableFrom(field.getPropertyType()) ? "BIGINT" : "VARCHAR(100000)";
            columns.add(field.getColumn() + " " + type + (field.getColumn().equals("vllm_model_id") ? " DEFAULT 'VLLM_ChatGLMVLLM'" : ""));
        }
        jdbc.execute("CREATE TABLE ai_agent_template(" + String.join(",", columns) + ")");
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        service = new OpsTemplateService(new SqlSessionTemplate(sessions).getMapper(AgentTemplateDao.class), mock(OpsApprovalService.class), mock(OpsIdentityService.class), new ObjectMapper());
        UserDetail employee = new UserDetail(); employee.setAudience("OPS"); employee.setEmployeeId("resources");
        employee.setGrants(List.of(new OpsGrant("OFFICIAL_CONTENT", "PLATFORM", "official-resources", Set.of("official.resource.read", "official.resource.write"))));
        security = mockStatic(SecurityUser.class); security.when(SecurityUser::getUser).thenReturn(employee);
    }
    @AfterEach void close() { security.close(); }

    @Test void absentModelSelectionsStayNullInStoredDraftCloneAndApprovalPayload() {
        jdbc.update("INSERT INTO ai_agent_template(id) VALUES('legacy-fixture')");
        assertEquals("VLLM_ChatGLMVLLM", jdbc.queryForObject("SELECT vllm_model_id FROM ai_agent_template WHERE id='legacy-fixture'", String.class));
        Map<String,Object> created = tx.execute(status -> service.create(input()));
        String id = created.get("id").toString();
        assertEmptyReferences(created);
        assertEmptyReferences(service.get(id));
        assertEmptyReferences(approvalPayload(id));
        assertNull(jdbc.queryForObject("SELECT vllm_model_id FROM ai_agent_template WHERE id=?", String.class, id));
        Map<String,Object> clone = tx.execute(status -> service.cloneDraft(id));
        assertEmptyReferences(service.get(clone.get("id").toString()));
        assertEquals("DRAFT", clone.get("reviewStatus"));
    }

    @Test void clearingOptionalFieldsPersistsNullAndPreparationUsesTheUpdatedSnapshot() {
        Map<String,Object> input = input();
        REFERENCES.forEach(field -> input.put(field, "configured-reference"));
        input.put("language", "中文"); input.put("langCode", "zh-CN");
        Map<String,Object> created = tx.execute(status -> service.create(input));
        String id = created.get("id").toString();
        assertEquals("configured-reference", service.get(id).get("vllmModelId"));
        Map<String,Object> cleared = input();
        for (int index = 0; index < REFERENCES.size(); index++) cleared.put(REFERENCES.get(index), index % 2 == 0 ? null : "  ");
        cleared.put("language", ""); cleared.put("langCode", null);
        Map<String,Object> updated = tx.execute(status -> service.update(id, cleared));
        Map<String,Object> persisted = service.get(id);
        assertEmptyReferences(updated); assertEmptyReferences(persisted); assertEmptyReferences(approvalPayload(id));
        assertEquals(2, persisted.get("version")); assertEquals("2", service.prepare(id).get("version"));
        assertNull(persisted.get("language")); assertNull(persisted.get("langCode"));
        assertEquals(updated, persisted);
    }

    @Test void uneditableOwnershipAndPrivateContentFieldsCannotBeIntroducedByExplicitSets() {
        Map<String,Object> input = input();
        input.put("workspaceId", "tenant-workspace"); input.put("sourceAgentId", "tenant-agent");
        input.put("summaryMemory", "private-memory-fixture"); input.put("reviewStatus", "APPROVED");
        Map<String,Object> created = tx.execute(status -> service.create(input));
        String id = created.get("id").toString();
        tx.executeWithoutResult(status -> service.update(id, input));
        var row = jdbc.queryForMap("SELECT workspace_id,source_agent_id,summary_memory,review_status FROM ai_agent_template WHERE id=?", id);
        assertNull(row.get("workspace_id")); assertNull(row.get("source_agent_id")); assertNull(row.get("summary_memory"));
        assertEquals("DRAFT", row.get("review_status"));
    }

    private Map<String,Object> input() { return new LinkedHashMap<>(Map.of("agentName", "AUDIT unit fixture", "templateName", "AUDIT unit fixture", "systemPrompt", "Public test-only greeting")); }
    @SuppressWarnings("unchecked") private Map<String,Object> approvalPayload(String id) { return (Map<String,Object>) service.prepare(id).get("payload"); }
    private void assertEmptyReferences(Map<String,Object> data) { REFERENCES.forEach(field -> assertNull(data.get(field), field)); }
}

package xiaozhi.modules.security.ops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.context.MessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import xiaozhi.common.security.CredentialCipher;
import xiaozhi.common.user.UserDetail;
import xiaozhi.common.utils.MessageUtils;
import xiaozhi.modules.security.password.PasswordUtils;
import xiaozhi.modules.security.user.SecurityUser;
import com.fasterxml.jackson.databind.ObjectMapper;

class OpsSecurityTest {
    private JdbcTemplate jdbc;
    private OpsIdentityService identities;
    private CredentialCipher cipher;
    private MockedStatic<SecurityUser> security;
    private UserDetail principal;
    private static final String TOTP="GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    @BeforeEach void setup() throws Exception {
        var source=new DriverManagerDataSource("jdbc:h2:mem:ops-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE ai_ota(type VARCHAR(100), version VARCHAR(100))");
        jdbc.execute("CREATE TABLE ai_rag_dataset(id VARCHAR(36) PRIMARY KEY,dataset_id VARCHAR(36),name VARCHAR(100),description VARCHAR(2000),rag_model_id VARCHAR(36),workspace_id VARCHAR(36),scope VARCHAR(30),permission VARCHAR(20),chunk_method VARCHAR(30),parser_config CLOB,chunk_count BIGINT,document_count BIGINT,token_num BIGINT,status INT,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE ai_rag_knowledge_chunk(id VARCHAR(36) PRIMARY KEY,dataset_id VARCHAR(36),document_id VARCHAR(36),chunk_id VARCHAR(36),content VARCHAR(100000),question VARCHAR(20000),answer VARCHAR(50000),sort_order INT,token_count BIGINT,enabled INT,edit_version BIGINT)");
        jdbc.execute("CREATE TABLE ai_rag_knowledge_document(id VARCHAR(36) PRIMARY KEY,dataset_id VARCHAR(36),document_id VARCHAR(36),name VARCHAR(100),size BIGINT,type VARCHAR(20),chunk_method VARCHAR(20),parser_config CLOB,status VARCHAR(10),run VARCHAR(20),progress DOUBLE,source_type VARCHAR(20),chunk_count INT,token_count BIGINT,enabled INT,content_version BIGINT,index_dirty INT)");
        jdbc.execute("CREATE TABLE sys_workspace(id VARCHAR(36), default_workspace INT)");
        jdbc.execute("CREATE TABLE sys_workspace_member(workspace_id VARCHAR(36), status INT)");
        String sql=Files.readString(Path.of("src/main/resources/db/changelog/202610121600.sql"));
        sql=sql.substring(sql.indexOf("CREATE TABLE ops_employee"));
        try(var connection=source.getConnection()) {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,new org.springframework.core.io.ByteArrayResource(sql.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        cipher=new CredentialCipher("test-only-encryption-master-key-at-least-32-chars");
        identities=new OpsIdentityService(jdbc,cipher);
        MessageSource messages=mock(MessageSource.class);
        when(messages.getMessage(anyString(),any(),any())).thenReturn("no permission");
        ReflectionTestUtils.setField(MessageUtils.class,"messageSource",messages);
        security=mockStatic(SecurityUser.class);
        security.when(SecurityUser::getUser).thenAnswer(call->principal);
        security.when(SecurityUser::getUserId).thenAnswer(call->principal==null?null:principal.getId());
    }
    @AfterEach void cleanup(){if(security!=null)security.close();}
    @Test void totpUsesRfcVectorAndRejectsReplay() {
        assertEquals("287082",OpsTotp.code(TOTP,1));
        assertEquals(1,OpsTotp.verify(TOTP,"287082",59,-1));
        assertEquals(-1,OpsTotp.verify(TOTP,"287082",59,1));
        assertEquals(-1,OpsTotp.verify(TOTP,"123",59,-1));
    }
    @Test void employeeSessionHasNoTenantIdentityAndMfaCannotBeReplayed() {
        employee("employee","operator");
        String otp=OpsTotp.code(TOTP,Instant.now().getEpochSecond()/30);
        String token=identities.login("operator","Strong-Test-Password-42",otp,"192.0.2.1").get("token").toString();
        assertFalse(jdbc.queryForObject("SELECT token_hash FROM ops_session",String.class).contains(token));
        UserDetail user=identities.authenticate(token);
        assertEquals("OPS",user.getAudience());assertEquals("employee",user.getEmployeeId());assertNull(user.getId());
        assertThrows(RuntimeException.class,()->identities.login("operator","Strong-Test-Password-42",otp,"192.0.2.1"));
        identities.revokeSessions("employee");
        assertThrows(RuntimeException.class,()->identities.authenticate(token));
    }
    @Test void scopeMatchingRejectsWildcardWrongDomainAndUserSession() {
        principal=ops("employee","SUPPLIER_FINANCE");
        assertTrue(OpsAccess.has("supplier.bill.read","PLATFORM","platform-services"));
        assertFalse(OpsAccess.has("supplier.bill.read","PLATFORM","other-domain"));
        assertThrows(RuntimeException.class,()->OpsPermissionCatalog.validate("SUPPLIER_FINANCE","PLATFORM","*"));
        principal.setAudience("USER");principal.setId(1L);
        assertFalse(OpsAccess.has("supplier.bill.read"));
    }
    @Test void userAndOperationsSessionsCannotCrossApiAudiences() throws Exception {
        var boundary=new SessionAudienceBoundary();
        var method=new HandlerMethod(this,OpsSecurityTest.class.getDeclaredMethod("endpoint"));
        principal=ops("employee","SUPPLIER_FINANCE");
        var content=new MockHttpServletRequest("GET","/agent/a/chat-history/s");
        assertThrows(RuntimeException.class,()->boundary.preHandle(content,new MockHttpServletResponse(),method));
        principal=new UserDetail();principal.setId(9L);principal.setAudience("USER");
        assertThrows(RuntimeException.class,()->boundary.preHandle(new MockHttpServletRequest("GET","/ops/auth/me"),new MockHttpServletResponse(),method));
        assertTrue(boundary.preHandle(content,new MockHttpServletResponse(),method));
    }
    public void endpoint() {}
    @Test void grantRequiresIndependentApproverAndDelegationForBothPeople() {
        employee("requester","requester");employee("approver","approver");employee("target","target");
        grant("requester","IAM_REQUEST");grant("approver","IAM_APPROVE");
        delegate("requester","ALLOWANCE");
        var service=new OpsIamService(jdbc,identities);
        principal=ops("requester","IAM_REQUEST");
        var request=new OpsIamService.GrantRequest("target","ALLOWANCE","PLATFORM","platform-services",Instant.now().plusSeconds(3600));
        String id=service.request(request);
        principal=ops("requester","IAM_APPROVE");
        assertThrows(RuntimeException.class,()->service.approve(id));
        principal=ops("approver","IAM_APPROVE");
        assertThrows(RuntimeException.class,()->service.approve(id));
        assertTrue(identities.grants("target").isEmpty());
        delegate("approver","ALLOWANCE");service.approve(id);
        assertEquals("ALLOWANCE",identities.grants("target").getFirst().roleCode());
        assertThrows(RuntimeException.class,()->service.approve(id));
    }
    @Test void revokedRequesterCannotHaveOldPendingRequestApproved() {
        employee("requester","requester");employee("approver","approver");employee("target","target");
        grant("requester","IAM_REQUEST");grant("approver","IAM_APPROVE");delegate("requester","ALLOWANCE");delegate("approver","ALLOWANCE");
        var service=new OpsIamService(jdbc,identities);principal=ops("requester","IAM_REQUEST");
        String id=service.request(new OpsIamService.GrantRequest("target","ALLOWANCE","PLATFORM","platform-services",Instant.now().plusSeconds(3600)));
        jdbc.update("UPDATE ops_role_grant SET status=0 WHERE employee_id='requester'");principal=ops("approver","IAM_APPROVE");
        assertThrows(RuntimeException.class,()->service.approve(id));
        assertTrue(identities.grants("target").isEmpty());
    }
    @Test void bootstrapIsOneTimeAndNeverReopensWhenEmployeesAreRemoved() throws Exception {
        var template=OpsPermissionCatalog.TEMPLATES.get("IAM_REQUEST");
        var first=new OpsBootstrap.Employee("requester","Initial-Password-42",TOTP,List.of(new OpsBootstrap.Assignment(template.code(),"PLATFORM",template.domain())),List.of());
        var second=new OpsBootstrap.Employee("approver","Another-Password-42",TOTP,List.of(new OpsBootstrap.Assignment("IAM_APPROVE","PLATFORM","identity")),List.of());
        Path file=Files.createTempFile("ops-bootstrap-test-",".json");
        try {
            Files.writeString(file,new ObjectMapper().writeValueAsString(new OpsBootstrap.Manifest(List.of(first,second))));
            var bootstrap=new OpsBootstrap(jdbc,cipher,new ObjectMapper(),new DataSourceTransactionManager(jdbc.getDataSource()));
            ReflectionTestUtils.setField(bootstrap,"manifestFile",file.toString());bootstrap.run(null);
            assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM ops_employee",Integer.class));
            var enrollment=new OpsEnrollmentService(jdbc,cipher,identities,transactions());
            var request=enrollment.submit("new-employee","Employee-Password-42",TOTP,OpsTotp.code(TOTP,Instant.now().getEpochSecond()/30),null,"192.0.2.2");
            assertEquals("PENDING",request.get("status"));
            assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM ops_employee",Integer.class));
            jdbc.update("DELETE FROM ops_employee");assertThrows(RuntimeException.class,()->bootstrap.run(null));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM ops_employee",Integer.class));
        } finally { Files.deleteIfExists(file); }
    }
    @Test void anonymousEnrollmentAndRecoveryCannotCreateEmployeesBeforeBootstrap() {
        var enrollment=new OpsEnrollmentService(jdbc,cipher,identities,transactions());
        String otp=OpsTotp.code(TOTP,Instant.now().getEpochSecond()/30);
        for(String recovery:Arrays.asList(null,"test-recovery-code")) {
            var error=assertThrows(xiaozhi.common.exception.RenException.class,()->enrollment.submit("new-employee","Employee-Password-42",TOTP,otp,recovery,"192.0.2.2"));
            assertEquals("运营身份尚未初始化，请联系部署负责人",error.getMessage());
        }
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM ops_employee",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM ops_identity_change",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT consumed FROM ops_bootstrap_state WHERE id=1",Integer.class));
    }
    @Test void roleSeparationPreventsPoolSelfFundingAndSelfReview() {
        assertFalse(OpsPermissionCatalog.TEMPLATES.get("SUPPLIER_FINANCE").permissions().contains("pool.adjust"));
        for(Set<String> roles:List.of(Set.of("ALLOWANCE","ALLOWANCE_POOL"),Set.of("ALLOWANCE_POOL","ALLOWANCE_REVIEW"),Set.of("IAM_REQUEST","SUPPORT"),Set.of("OTA","OTA_REVIEW"),Set.of("OFFICIAL_CONTENT","CONTENT_REVIEW"),Set.of("MODEL_CATALOG","MODEL_REVIEW")))
            assertThrows(RuntimeException.class,()->OpsPermissionCatalog.requireCompatible(roles));
    }
    @Test void employeeChoosesFactorsAndNeedsTwoIndependentIdentityApprovals() {
        jdbc.update("UPDATE ops_bootstrap_state SET consumed=1 WHERE id=1");
        employee("requester","requester");employee("approver","approver");grant("requester","IAM_REQUEST");grant("approver","IAM_APPROVE");
        var enrollment=new OpsEnrollmentService(jdbc,cipher,identities,transactions());
        String otp=OpsTotp.code(TOTP,Instant.now().getEpochSecond()/30);
        var result=enrollment.submit("new-employee","Employee-Password-42",TOTP,otp,null,"192.0.2.2");
        String id=result.get("id").toString(),employee=result.get("employeeId").toString();
        assertThrows(RuntimeException.class,()->identities.login("new-employee","Employee-Password-42",otp,"192.0.2.2"));
        principal=ops("requester","IAM_REQUEST");enrollment.endorse(id);
        assertFalse(enrollment.list().toString().contains(result.get("recoveryCode").toString()));
        assertFalse(enrollment.list().toString().contains("mfa_cipher"));
        principal=ops("requester","IAM_APPROVE");assertThrows(RuntimeException.class,()->enrollment.approve(id));
        principal=ops("approver","IAM_APPROVE");enrollment.approve(id);
        assertEquals(1,jdbc.queryForObject("SELECT status FROM ops_employee WHERE id=?",Integer.class,employee));
        assertTrue(identities.grants(employee).isEmpty());
        assertEquals(OpsIdentityService.hash(result.get("recoveryCode").toString()),jdbc.queryForObject("SELECT recovery_hash FROM ops_employee WHERE id=?",String.class,employee));
        assertNull(jdbc.queryForObject("SELECT mfa_cipher FROM ops_identity_change WHERE id=?",String.class,id));
        assertThrows(RuntimeException.class,()->enrollment.approve(id));
    }
    @Test void recoveryNeverRestoresRevokedRolesAndConsumesRecoveryCode() {
        jdbc.update("UPDATE ops_bootstrap_state SET consumed=1 WHERE id=1");
        employee("requester","requester");employee("approver","approver");employee("target","target");grant("requester","IAM_REQUEST");grant("approver","IAM_APPROVE");grant("target","ALLOWANCE");
        jdbc.update("UPDATE ops_role_grant SET status=0 WHERE employee_id='target'");
        jdbc.update("UPDATE ops_employee SET recovery_hash=? WHERE id='target'",OpsIdentityService.hash("test-recovery-code"));
        var enrollment=new OpsEnrollmentService(jdbc,cipher,identities,transactions());String otp=OpsTotp.code(TOTP,Instant.now().getEpochSecond()/30);
        var request=enrollment.submit("target","Changed-Password-42",TOTP,otp,"test-recovery-code","192.0.2.3");String id=request.get("id").toString();
        assertThrows(RuntimeException.class,()->enrollment.submit("target","Changed-Password-42",TOTP,otp,"test-recovery-code","192.0.2.3"));
        principal=ops("requester","IAM_REQUEST");enrollment.endorse(id);principal=ops("approver","IAM_APPROVE");enrollment.approve(id);
        assertTrue(identities.grants("target").isEmpty());
        assertTrue(PasswordUtils.matches("Changed-Password-42",jdbc.queryForObject("SELECT password_hash FROM ops_employee WHERE id='target'",String.class)));
    }
    @Test void approvalBindsPayloadActorVersionAndIsConsumedOnlyWithCommittedMutation() {
        employee("writer","writer");employee("reviewer","reviewer");grant("writer","OFFICIAL_CONTENT");grant("reviewer","CONTENT_REVIEW");
        var approvals=approvals();principal=ops("writer","OFFICIAL_CONTENT");var payload=Map.of("name","approved");
        String id=approvals.propose(new OpsApprovalService.Proposal("official.resource.publish","resource","1",new ObjectMapper().valueToTree(payload))).get("id").toString();
        principal=ops("reviewer","CONTENT_REVIEW");approvals.approve(id);principal=ops("writer","OFFICIAL_CONTENT");
        assertThrows(RuntimeException.class,()->approvals.execute(id,"official.resource.publish","resource","1",Map.of("name","changed"),()->null));
        assertThrows(RuntimeException.class,()->approvals.execute(id,"official.resource.publish","resource","2",payload,()->null));
        assertThrows(RuntimeException.class,()->approvals.execute(id,"official.resource.publish","resource","1",payload,()->{jdbc.update("UPDATE ops_employee SET status=0 WHERE id='writer'");throw new IllegalStateException("rollback");}));
        assertEquals("APPROVED",jdbc.queryForObject("SELECT status FROM ops_operation_approval WHERE id=?",String.class,id));
        assertEquals(1,jdbc.queryForObject("SELECT status FROM ops_employee WHERE id='writer'",Integer.class));
        assertEquals("published",approvals.execute(id,"official.resource.publish","resource","1",payload,()->"published"));
        assertThrows(RuntimeException.class,()->approvals.execute(id,"official.resource.publish","resource","1",payload,()->null));
    }
    @Test void disabledReviewerInvalidatesAlreadyApprovedPublication() {
        employee("writer","writer");employee("reviewer","reviewer");grant("writer","OFFICIAL_CONTENT");grant("reviewer","CONTENT_REVIEW");
        var approvals=approvals();principal=ops("writer","OFFICIAL_CONTENT");var payload=Map.of("name","approved");
        String id=approvals.propose(new OpsApprovalService.Proposal("official.resource.publish","resource","1",new ObjectMapper().valueToTree(payload))).get("id").toString();
        principal=ops("reviewer","CONTENT_REVIEW");approvals.approve(id);jdbc.update("UPDATE ops_employee SET status=0 WHERE id='reviewer'");principal=ops("writer","OFFICIAL_CONTENT");
        assertThrows(RuntimeException.class,()->approvals.execute(id,"official.resource.publish","resource","1",payload,()->null));
    }
    @Test void publicKnowledgeDraftPublishesExactSnapshotAndCannotReadPrivateDataset() {
        employee("writer","writer");employee("reviewer","reviewer");grant("writer","OFFICIAL_CONTENT");grant("reviewer","CONTENT_REVIEW");
        var models=mock(xiaozhi.modules.model.dao.ModelConfigDao.class);var model=new xiaozhi.modules.model.entity.ModelConfigEntity();model.setModelType("RAG");model.setAdapterCode("local_knowledge");when(models.selectById("rag")).thenReturn(model);
        var approvals=approvals();var service=new OpsKnowledgeService(jdbc,models,approvals,identities);principal=ops("writer","OFFICIAL_CONTENT");
        var input=new OpsKnowledgeService.Draft("Public FAQ","approved text","rag",List.of(new OpsKnowledgeService.Entry("Question","Answer",null)));
        String id=service.create(input).get("id").toString();var proposal=service.prepare(id);
        String approval=approvals.propose(new OpsApprovalService.Proposal(proposal.get("action").toString(),id,proposal.get("version").toString(),new ObjectMapper().valueToTree(proposal.get("payload")))).get("id").toString();
        principal=ops("reviewer","CONTENT_REVIEW");approvals.approve(approval);principal=ops("writer","OFFICIAL_CONTENT");
        var request=new MockHttpServletRequest();request.addHeader("X-Ops-Approval-Id",approval);
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new org.springframework.web.context.request.ServletRequestAttributes(request));
        try {new org.springframework.transaction.support.TransactionTemplate(transactions()).executeWithoutResult(tx->service.publish(id));}
        finally {org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();}
        assertEquals("OFFICIAL",service.get(id).get("scope"));assertEquals(1,jdbc.queryForObject("SELECT status FROM ai_rag_dataset WHERE id=?",Integer.class,id));
        assertThrows(RuntimeException.class,()->service.update(id,input));
        jdbc.update("INSERT INTO ai_rag_dataset(id,dataset_id,workspace_id,scope,name) VALUES('private','private','user-space','WORKSPACE','Private text')");
        assertThrows(RuntimeException.class,()->service.cloneDraft("private"));assertFalse(service.list().toString().contains("Private text"));
        var cloned=service.cloneDraft(id);assertEquals("OFFICIAL_DRAFT",cloned.get("scope"));assertNotEquals(id,cloned.get("id"));
    }
    private DataSourceTransactionManager transactions(){return new DataSourceTransactionManager(jdbc.getDataSource());}
    @Test void templatesRejectPrivateSourceAndPublishedOverwriteAndIgnorePrivateFields() {
        var dao=mock(xiaozhi.modules.agent.dao.AgentTemplateDao.class);
        var service=new OpsTemplateService(dao,approvals(),identities,new ObjectMapper());
        principal=ops("writer","OFFICIAL_CONTENT");
        var privateTemplate=new xiaozhi.modules.agent.entity.AgentTemplateEntity();privateTemplate.setId("private");privateTemplate.setWorkspaceId("workspace");when(dao.selectById("private")).thenReturn(privateTemplate);
        assertThrows(RuntimeException.class,()->service.cloneDraft("private"));
        var published=new xiaozhi.modules.agent.entity.AgentTemplateEntity();published.setId("published");published.setReviewStatus("APPROVED");when(dao.selectById("published")).thenReturn(published);
        assertThrows(RuntimeException.class,()->service.update("published",Map.of("agentName","changed","systemPrompt","prompt")));
        service.create(Map.of("agentName","Official","systemPrompt","Public instructions","summaryMemory","private memory","workspaceId","workspace","sourceAgentId","user-agent"));
        var captor=org.mockito.ArgumentCaptor.forClass(xiaozhi.modules.agent.entity.AgentTemplateEntity.class);verify(dao).insert(captor.capture());
        assertNull(captor.getValue().getWorkspaceId());assertNull(captor.getValue().getSourceAgentId());assertNull(captor.getValue().getSummaryMemory());assertEquals("DRAFT",captor.getValue().getReviewStatus());
    }
    private OpsApprovalService approvals(){return new OpsApprovalService(jdbc,new ObjectMapper(),identities,transactions());}
    private void employee(String id,String name){jdbc.update("INSERT INTO ops_employee(id,username,password_hash,mfa_cipher) VALUES(?,?,?,?)",id,name,PasswordUtils.encode("Strong-Test-Password-42"),cipher.encrypt("ops-mfa:"+id,TOTP));}
    private void grant(String employee,String role){var t=OpsPermissionCatalog.TEMPLATES.get(role);jdbc.update("INSERT INTO ops_role_grant(id,employee_id,role_code,scope_type,scope_id) VALUES(?,?,?,'PLATFORM',?)",UUID.randomUUID().toString(),employee,role,t.domain());}
    private void delegate(String employee,String role){var t=OpsPermissionCatalog.TEMPLATES.get(role);jdbc.update("INSERT INTO ops_delegation(id,employee_id,role_code,scope_type,scope_id) VALUES(?,?,?,'PLATFORM',?)",UUID.randomUUID().toString(),employee,role,t.domain());}
    private UserDetail ops(String employee,String role){var t=OpsPermissionCatalog.TEMPLATES.get(role);UserDetail user=new UserDetail();user.setEmployeeId(employee);user.setAudience("OPS");user.setGrants(List.of(new OpsGrant(role,"PLATFORM",t.domain(),t.permissions())));return user;}
}

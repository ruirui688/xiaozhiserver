package xiaozhi.modules.platform.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.security.CredentialCipher;
import xiaozhi.modules.billing.service.*;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.platform.dto.PlatformRequests.*;
import xiaozhi.modules.workspace.service.WorkspacePermissionService;

class PlatformAdmissionServiceTest {
    JdbcTemplate jdbc;TransactionTemplate tx;BillingSupport json;
    PlatformCredentialService credentials;PlatformAccountService accounts;
    PlatformResponsibilityService responsibilities;PlatformAdmissionService admission;
    PlatformRateService rates;PlatformServiceAccountService services;
    @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:platform"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000","sa","");
        jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));json=new BillingSupport(new ObjectMapper());
        jdbc.execute("CREATE TABLE sys_user(id bigint PRIMARY KEY,status int)");
        jdbc.execute("CREATE TABLE sys_workspace(id varchar(64) PRIMARY KEY,owner_user_id bigint,default_workspace int,status int)");
        jdbc.execute("CREATE TABLE sys_workspace_member(workspace_id varchar(64),user_id bigint,status int)");
        jdbc.execute("CREATE TABLE ai_agent(id varchar(64),workspace_id varchar(64))");
        jdbc.execute("CREATE TABLE ai_device(id varchar(64),agent_id varchar(64),workspace_id varchar(64))");
        jdbc.execute("CREATE TABLE ai_model_config(id varchar(64),model_type varchar(16))");
        String sql=Files.readString(Path.of("src/main/resources/db/changelog/202610121610.sql"));
        sql=sql.replaceAll("(?m)^--.*$","").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","").replaceAll("INSERT INTO ai_platform_account\\(user_id,updated_at\\) SELECT[^;]*;","").replaceAll("INSERT INTO ai_platform_responsibility\\(workspace_id,responsible_user_id,version\\)[^;]*;","");
        for(String statement:sql.split(";"))if(!statement.isBlank())jdbc.execute(statement);
        jdbc.update("INSERT INTO sys_user VALUES(1,1),(2,1)");
        jdbc.update("INSERT INTO sys_workspace VALUES('personal',1,1,1),('team-a',1,0,1),('team-b',1,0,1)");
        jdbc.update("INSERT INTO sys_workspace_member VALUES('personal',1,1),('team-a',1,1),('team-a',2,1),('team-b',1,1)");
        jdbc.update("INSERT INTO ai_model_config VALUES('model','LLM')");
        var cipher=new CredentialCipher("test-master-key-only-32-characters-long");
        credentials=new PlatformCredentialService(jdbc,tx,json,cipher);accounts=new PlatformAccountService(jdbc,tx,credentials);
        accounts.ensureUser(1);accounts.ensureUser(2);
        for(long id:List.of(1L,2L)) {credentials.add(new CredentialInput("DEEPSEEK","test-upstream-"+id,Map.of("api_key","not-a-real-key-"+id)));credentials.assign(id,"DEEPSEEK",false);}
        responsibilities=new PlatformResponsibilityService(jdbc,tx,mock(WorkspacePermissionService.class),credentials);
        for(String workspace:List.of("personal","team-a","team-b"))responsibilities.ensureWorkspace(workspace);
        accounts.adjust(new PoolAdjustment("pool-fixture","1000","fixture"),"operator");accounts.grant(new Grant("grant-fixture",1L,"10","fixture"),"operator");
        rates=new PlatformRateService(jdbc,json);rates.add(new Rate("model","v1",Map.of("input_tokens","0.001","output_tokens","0.001"),Map.of("input_tokens","10000","output_tokens","10000")));
        services=new PlatformServiceAccountService(jdbc,tx,json);
        PlatformRuntimeService runtime=mock(PlatformRuntimeService.class);
        when(runtime.approved(eq("model"),eq("LLM"),anyBoolean())).thenReturn(new PlatformRuntimeService.Runtime(new ModelConfigEntity(),"DEEPSEEK",Map.of("base_url","https://api.deepseek.com/v1","model_name","deepseek-chat")));
        BillingContextService contexts=mock(BillingContextService.class);
        when(contexts.verify(anyString(),anyString(),eq("model"),any())).thenAnswer(call->Map.of("workspace_id",call.getArgument(0),"purpose","CONVERSATION"));
        admission=new PlatformAdmissionService(jdbc,tx,json,contexts,accounts,credentials,responsibilities,rates,runtime,services,cipher);
    }
    Admit request(String workspace,String attempt,int quantity) {return new Admit(workspace,"token","model","LLM","CONVERSATION",attempt,attempt,Map.of("input_tokens",Integer.toString(quantity),"output_tokens","0"));}
    Permit permit(Map<String,Object> grant) {return new Permit(grant.get("reservationId").toString(),grant.get("permitToken").toString());}
    Settle usage(Permit permit,int quantity) {return new Settle(permit.reservationId(),permit.permitToken(),"SUCCESS",true,Map.of("input_tokens",Integer.toString(quantity),"output_tokens","0"),"provider-test-request");}
    BigDecimal balance(String field,long user){return jdbc.queryForObject("SELECT "+field+" FROM ai_platform_account WHERE user_id=?",BigDecimal.class,user);}

    @Test void concurrentTeamsCannotOverspendSharedPayer() throws Exception {
        var start=new CountDownLatch(1);List<Future<Boolean>> tasks=new ArrayList<>();
        try(var workers=Executors.newFixedThreadPool(2)) {
            for(String team:List.of("team-a","team-b"))tasks.add(workers.submit(()->{start.await();try{admission.admit(request(team,"attempt-"+team,6000));return true;}catch(RenException rejected){return false;}}));
            start.countDown();int success=0;for(var task:tasks)if(task.get(10,TimeUnit.SECONDS))success++;assertEquals(1,success);
        }
        assertEquals(0,new BigDecimal("6").compareTo(balance("reserved",1)));assertEquals(0,BigDecimal.ZERO.compareTo(balance("spent",1)));
    }
    @Test void idempotencyDoesNotReserveOrChargeTwiceAndRejectsChangedPayload() {
        var request=request("personal","attempt-repeat",5000);var first=admission.admit(request);var repeated=admission.admit(request);
        assertEquals(first.get("reservationId"),repeated.get("reservationId"));assertEquals(first.get("permitToken"),repeated.get("permitToken"));
        assertThrows(RenException.class,()->admission.admit(request("personal","attempt-repeat",4000)));
        Permit permit=permit(first);admission.start(permit);var settlement=usage(permit,2000);admission.settle(settlement);admission.settle(settlement);
        assertEquals(0,new BigDecimal("2").compareTo(balance("spent",1)));assertEquals(0,BigDecimal.ZERO.compareTo(balance("reserved",1)));
        assertThrows(RenException.class,()->admission.settle(usage(permit,1000)));
    }
    @Test void unknownAndMissingUsageRetainReservationAndCannotReleaseStartedCalls() {
        Permit permit=permit(admission.admit(request("team-a","attempt-unknown",5000)));admission.start(permit);
        assertEquals("UNKNOWN",admission.settle(new Settle(permit.reservationId(),permit.permitToken(),"UNKNOWN",false,Map.of(),null)).get("state"));
        assertThrows(RenException.class,()->admission.release(permit));assertEquals(0,new BigDecimal("5").compareTo(balance("reserved",1)));
        assertEquals("SETTLED",admission.settle(usage(permit,3000)).get("state"));
    }
    @Test void teamLimitsAndFrozenOrDisabledPayerBlockBeforeDispatch() {
        responsibilities.limits(new WorkspaceLimits("team-a","3","2",1),1);
        assertThrows(RenException.class,()->admission.admit(request("team-a","attempt-limit",3000)));
        Permit pending=permit(admission.admit(request("team-a","attempt-freeze",1000)));
        accounts.freeze(new Freeze("freeze-fixture",1L,true,"investigate"),"operator");assertEquals(false,admission.start(pending).get("started"));
        accounts.freeze(new Freeze("unfreeze-fixture",1L,false,"resolved"),"operator");jdbc.update("UPDATE sys_user SET status=0 WHERE id=1");
        assertThrows(RenException.class,()->admission.admit(request("personal","attempt-disabled",1000)));
    }
    @Test void expiryReleasesOnlyUnstartedAndExcessUsageStaysUnknown() {
        Permit pending=permit(admission.admit(request("personal","attempt-expired",1000)));
        jdbc.update("UPDATE ai_platform_reservation SET expires_at=? WHERE id=?",PlatformSupport.now().minusMinutes(1),pending.reservationId());admission.expireReservations();
        assertEquals(0,BigDecimal.ZERO.compareTo(balance("reserved",1)));
        Permit started=permit(admission.admit(request("personal","attempt-overlimit",1000)));admission.start(started);
        assertEquals("UNKNOWN",admission.settle(usage(started,2000)).get("state"));assertEquals(0,new BigDecimal("1").compareTo(balance("reserved",1)));
    }
    @Test void credentialsAreEncryptedUniqueAndRotationDoesNotReassignOldKey() {
        String ciphertext=jdbc.queryForObject("SELECT secret_ciphertext FROM ai_platform_credential WHERE user_id=1",String.class);
        assertTrue(ciphertext.startsWith("v1:"));assertFalse(ciphertext.contains("not-a-real-key"));assertFalse(json.json(credentials.list()).contains("not-a-real-key"));
        assertThrows(org.springframework.dao.DuplicateKeyException.class,()->credentials.add(new CredentialInput("DEEPSEEK","different-id",Map.of("api_key","not-a-real-key-1"))));
        credentials.add(new CredentialInput("DEEPSEEK","rotation-key",Map.of("api_key","not-a-real-rotated-key")));
        var request=new Assignment("rotation-fixture",1L,"DEEPSEEK");var first=credentials.assign(request,true);var repeated=credentials.assign(request,true);assertEquals(json.json(first),json.json(repeated));
        assertEquals(1L,jdbc.queryForObject("SELECT user_id FROM ai_platform_credential WHERE upstream_id='test-upstream-1'",Long.class));
    }
    @Test void serviceCredentialsCannotBeBorrowedAcrossVolcengineProducts() {
        var credential=credentials.add(new CredentialInput("VOLCENGINE","speech-only",Map.of("speech_api_key","fake-speech-key")));
        credentials.assign(1,"VOLCENGINE",false);String id=credential.get("id").toString();
        assertEquals(Map.of("api_key","fake-speech-key"),credentials.runtimeSecrets(id,1,"ASR"));
        assertThrows(RenException.class,()->credentials.runtimeSecrets(id,1,"LLM"));
    }
    @Test void revokedCredentialAndRemovedActorPreventStartButNotHistoricalSettlement() {
        Permit started=permit(admission.admit(request("team-a","attempt-before-revoke",1000)));admission.start(started);
        Permit waiting=permit(admission.admit(request("team-a","attempt-after-revoke",1000)));
        credentials.revoke(credentials.active(1,"DEEPSEEK").get("id").toString());
        assertEquals(false,admission.start(waiting).get("started"));assertEquals("SETTLED",admission.settle(usage(started,500)).get("state"));
    }
    @Test void nonpayerTeamMemberCannotReadResponsibleUsersPersonalBalance() {
        var summary=responsibilities.summary("team-a",2);assertEquals(false,summary.get("canViewBalance"));assertNull(summary.get("allowance"));
        assertThrows(RenException.class,()->responsibilities.limits(new WorkspaceLimits("team-a","100",null,1),2));
    }
    @Test void issuancePoolAndRequestConflictAreEnforced() {
        accounts.grant(new Grant("grant-fixture",1L,"10","fixture"),"operator");assertEquals(0,new BigDecimal("10").compareTo(balance("granted",1)));
        assertThrows(RenException.class,()->accounts.grant(new Grant("grant-fixture",1L,"11","fixture"),"operator"));
        assertThrows(RenException.class,()->accounts.adjust(new PoolAdjustment("pool-too-small","-999","invalid"),"operator"));
        assertThrows(RenException.class,()->accounts.grant(new Grant("grant-overflow",2L,"1001","too much"),"operator"));
    }
}

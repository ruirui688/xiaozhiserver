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
    PlatformRateService rates;PlatformServiceAccountService services;PlatformChannelService channels;PlatformRuntimeService runtime;
    @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:platform"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000","sa","");
        jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));json=new BillingSupport(new ObjectMapper());
        jdbc.execute("CREATE TABLE sys_user(id bigint PRIMARY KEY,status int)");
        jdbc.execute("CREATE TABLE sys_workspace(id varchar(64) PRIMARY KEY,owner_user_id bigint,default_workspace int,status int)");
        jdbc.execute("CREATE TABLE sys_workspace_member(workspace_id varchar(64),user_id bigint,status int)");
        jdbc.execute("CREATE TABLE ai_agent(id varchar(64),workspace_id varchar(64))");
        jdbc.execute("CREATE TABLE ai_device(id varchar(64),agent_id varchar(64),workspace_id varchar(64))");
        jdbc.execute("CREATE TABLE ai_model_config(id varchar(64),model_type varchar(16),model_name varchar(100),is_enabled int,test_status int)");
        String sql=Files.readString(Path.of("src/main/resources/db/changelog/202610121610.sql"));
        sql=sql.replaceAll("(?m)^--.*$","").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","").replaceAll("INSERT INTO ai_platform_account\\(user_id,updated_at\\) SELECT[^;]*;","").replaceAll("INSERT INTO ai_platform_responsibility\\(workspace_id,responsible_user_id,version\\)[^;]*;","");
        for(String statement:sql.split(";"))if(!statement.isBlank())jdbc.execute(statement);
        jdbc.execute("CREATE TABLE ai_billing_usage_event(event_id varchar(64),started_at datetime)");
        String migration=Files.readString(Path.of("src/main/resources/db/changelog/platform-shared-channels-20261013.sql")).replaceAll("(?m)^--.*$", "").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", "");
        for(String statement:migration.split(";"))if(!statement.isBlank())jdbc.execute(statement);
        jdbc.update("INSERT INTO sys_user VALUES(1,1),(2,1)");
        jdbc.update("INSERT INTO sys_workspace VALUES('personal',1,1,1),('team-a',1,0,1),('team-b',1,0,1)");
        jdbc.update("INSERT INTO sys_workspace_member VALUES('personal',1,1),('team-a',1,1),('team-a',2,1),('team-b',1,1)");
        jdbc.update("INSERT INTO ai_model_config VALUES('model','LLM','Test model',1,1)");
        var cipher=new CredentialCipher("test-master-key-only-32-characters-long");
        credentials=new PlatformCredentialService(jdbc,tx,json,cipher);
        runtime=mock(PlatformRuntimeService.class);
        when(runtime.approved(eq("model"),eq("LLM"),anyBoolean())).thenReturn(new PlatformRuntimeService.Runtime(new ModelConfigEntity(),"DEEPSEEK",Map.of("base_url","https://api.deepseek.com/v1","model_name","deepseek-chat")));
        channels=new PlatformChannelService(jdbc,tx,json,cipher,runtime);
        channels.save(new PlatformChannelService.Input("channel-fixture",null,null,"DeepSeek","model","official-test-account",null,null,true,20,Map.of("api_key","shared-platform-key"),null));
        accounts=new PlatformAccountService(jdbc,tx,channels);
        accounts.ensureUser(1);accounts.ensureUser(2);
        for(long id:List.of(1L,2L)) {credentials.add(new CredentialInput("DEEPSEEK","test-upstream-"+id,Map.of("api_key","not-a-real-key-"+id)));credentials.assign(id,"DEEPSEEK",false);}
        responsibilities=new PlatformResponsibilityService(jdbc,tx,mock(WorkspacePermissionService.class),channels);
        for(String workspace:List.of("personal","team-a","team-b"))responsibilities.ensureWorkspace(workspace);
        accounts.adjust(new PoolAdjustment("pool-fixture","1000","fixture"),"operator");accounts.grant(new Grant("grant-fixture",1L,"10","fixture"),"operator");
        rates=new PlatformRateService(jdbc,json);rates.add(new Rate("model","v1",Map.of("input_tokens","0.001","output_tokens","0.001"),Map.of("input_tokens","10000","output_tokens","10000")));
        services=new PlatformServiceAccountService(jdbc,tx,json);
        BillingContextService contexts=mock(BillingContextService.class);
        when(contexts.verify(anyString(),anyString(),eq("model"),any())).thenAnswer(call->Map.of("workspace_id",call.getArgument(0),"purpose","CONVERSATION"));
        admission=new PlatformAdmissionService(jdbc,tx,json,contexts,accounts,credentials,channels,responsibilities,rates,runtime,services,cipher);
    }
    Admit request(String workspace,String attempt,int quantity) {return new Admit(workspace,"token","model","LLM","CONVERSATION",attempt,attempt,Map.of("input_tokens",Integer.toString(quantity),"output_tokens","0"));}
    Permit permit(Map<String,Object> grant) {return new Permit(grant.get("reservationId").toString(),grant.get("permitToken").toString());}
    Settle usage(Permit permit,int quantity) {return new Settle(permit.reservationId(),permit.permitToken(),"SUCCESS",true,Map.of("input_tokens",Integer.toString(quantity),"output_tokens","0"),"provider-test-request");}
    BigDecimal balance(String field,long user){return jdbc.queryForObject("SELECT "+field+" FROM ai_platform_account WHERE user_id=?",BigDecimal.class,user);}

    @Test void summaryDistinguishesFrozenExhaustedUnconfiguredAndReadyWithoutExposingMemberBalance() {
        assertEquals("READY",responsibilities.summary("personal",1).get("serviceStatus"));
        jdbc.update("UPDATE ai_platform_channel SET status='DISABLED'");
        assertEquals("PLATFORM_SERVICE_NOT_READY",responsibilities.summary("personal",1).get("serviceStatus"));
        jdbc.update("UPDATE ai_platform_account SET reserved=granted WHERE user_id=1");
        assertEquals("ALLOWANCE_EXHAUSTED",responsibilities.summary("personal",1).get("serviceStatus"));
        jdbc.update("UPDATE ai_platform_account SET frozen=1 WHERE user_id=1");
        var member=responsibilities.summary("team-a",2);
        assertEquals("PAYER_FROZEN",member.get("serviceStatus"));assertEquals(false,member.get("canViewBalance"));assertNull(member.get("allowance"));
    }
    @Test void summaryRecognizesTotalAndDailyWorkspaceLimitsIncludingReservedUsage() {
        responsibilities.limits(new WorkspaceLimits("personal","0",null,1),1);
        assertEquals("WORKSPACE_ALLOWANCE_LIMIT",responsibilities.summary("personal",1).get("serviceStatus"));
        responsibilities.limits(new WorkspaceLimits("personal",null,"2",1),1);
        admission.admit(request("personal","fully-reserved-limit",2000));
        assertEquals("WORKSPACE_ALLOWANCE_LIMIT",responsibilities.summary("personal",1).get("serviceStatus"));
        assertEquals("READY",responsibilities.summary("team-a",1).get("serviceStatus"));
    }
    @Test void platformSettlementAndSummaryWorkWithoutAnySupplierInvoiceTables() {
        Permit call=permit(admission.admit(request("personal","invoice-independent",2000)));admission.start(call);
        var settled=admission.settle(usage(call,750));assertEquals("SETTLED",settled.get("state"));
        assertEquals(0,new BigDecimal("0.75").compareTo(balance("spent",1)));
        var summary=responsibilities.summary("personal",1);assertEquals("READY",summary.get("serviceStatus"));
        assertEquals(0,new BigDecimal("0.75").compareTo(new BigDecimal(summary.get("totalSpent").toString())));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE LOWER(TABLE_NAME)='ai_billing_bill'",Integer.class));
    }

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
    @Test void disabledChannelPreventsStartButNotHistoricalSettlement() {
        Permit started=permit(admission.admit(request("team-a","attempt-before-revoke",1000)));admission.start(started);
        Permit waiting=permit(admission.admit(request("team-a","attempt-after-revoke",1000)));
        jdbc.update("UPDATE ai_platform_channel SET status='DISABLED'");
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
    @Test void registrationNeverConsumesLegacyInventoryOrCreatesUpstreamPointers() {
        credentials.add(new CredentialInput("DEEPSEEK","unused-registration-key",Map.of("api_key","legacy-unused-key")));
        jdbc.update("INSERT INTO sys_user VALUES(3,1)");accounts.ensureUser(3);accounts.ensureUser(3);
        assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM ai_platform_account WHERE user_id=3",Long.class));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM ai_platform_user_credential WHERE user_id=3",Long.class));
        assertEquals("INVENTORY",jdbc.queryForObject("SELECT status FROM ai_platform_credential WHERE upstream_id='unused-registration-key'",String.class));
        assertFalse(accounts.account(3).containsKey("credentials"));assertNotNull(accounts.account(3).get("services"));
    }
    @Test void sharedChannelUsesDistinctPayersWhileCollaboratorUsesWorkspaceOwner() {
        jdbc.update("INSERT INTO sys_workspace VALUES('second-personal',2,1,1)");jdbc.update("INSERT INTO sys_workspace_member VALUES('second-personal',2,1)");
        responsibilities.ensureWorkspace("second-personal");accounts.grant(new Grant("second-payer-grant",2L,"10","fixture"),"operator");
        // Neither user's existing upstream key is necessary for the shared channel.
        jdbc.update("DELETE FROM ai_platform_user_credential");
        var first=admission.admitVerified(request("team-a","shared-first",1000),Map.of("workspace_id","team-a","purpose","CONVERSATION","actor_user_id",2L));
        var second=admission.admit(request("second-personal","shared-second",1000));
        assertEquals(first.get("credentials"),second.get("credentials"));
        var routeA=(Map<?,?>)first.get("route");var routeB=(Map<?,?>)second.get("route");
        assertEquals(1,((Number)routeA.get("payerUserId")).intValue());assertEquals(2,((Number)routeB.get("payerUserId")).intValue());
        assertEquals(routeA.get("channelId"),routeB.get("channelId"));assertFalse(routeA.containsKey("credentialOwnerUserId"));
        assertNotEquals(first.get("reservationId"),second.get("reservationId"));
        var a=permit(first);var b=permit(second);admission.start(a);admission.start(b);admission.settle(usage(a,700));admission.settle(usage(b,300));
        assertEquals(0,new BigDecimal("0.7").compareTo(balance("spent",1)));assertEquals(0,new BigDecimal("0.3").compareTo(balance("spent",2)));
    }
    PlatformChannelService.Input edit(String request,long version,boolean enabled,Map<String,String> secret,int concurrency) {
        String channel=jdbc.queryForObject("SELECT id FROM ai_platform_channel WHERE model_id='model'",String.class);
        return new PlatformChannelService.Input(request,channel,version,"DeepSeek","model","official-test-account",null,null,enabled,concurrency,secret,null);
    }
    @Test void rotatingChannelPreservesFrozenSecretsAndStoppingItPreservesStartedSettlement() {
        var before=admission.admit(request("personal","before-rotation",1000));var running=permit(before);admission.start(running);
        var change=edit("rotate-shared-key",1,true,Map.of("api_key","rotated-shared-key"),20);channels.save(change);channels.save(change);
        var after=admission.admit(request("personal","after-rotation",1000));
        assertEquals(Map.of("api_key","shared-platform-key"),before.get("credentials"));assertEquals(Map.of("api_key","rotated-shared-key"),after.get("credentials"));
        assertEquals(2L,jdbc.queryForObject("SELECT COUNT(*) FROM ai_platform_channel_credential",Long.class));
        channels.save(edit("disable-shared-key",2,false,null,20));
        assertEquals(false,admission.start(permit(after)).get("started"));
        assertThrows(RenException.class,()->admission.admit(request("personal","disabled-shared",1000)));
        assertNotNull(admission.extend(new Extend(running.reservationId(),running.permitToken(),Map.of("input_tokens","2000","output_tokens","0"))));
        assertEquals("SETTLED",admission.settle(usage(running,500)).get("state"));
    }
    @Test void channelSaveIsVersionedAtomicIdempotentAndNeverReturnsSecrets() {
        var input=edit("update-channel-label",1,true,null,20);var saved=channels.save(input);assertEquals(saved.get("id"),channels.save(input).get("id"));
        assertThrows(RenException.class,()->channels.save(edit("stale-channel-update",1,true,null,20)));
        assertThrows(RenException.class,()->channels.save(edit("update-channel-label",2,false,null,20)));
        assertThrows(RenException.class,()->channels.save(new PlatformChannelService.Input("duplicate-model-route",null,null,"Duplicate","model","another-account",null,null,true,20,Map.of("api_key","duplicate-secret"),null)));
        assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM ai_platform_channel_credential",Long.class));
        assertEquals(2L,jdbc.queryForObject("SELECT COUNT(*) FROM ai_platform_channel_operation",Long.class));
        String publicJson=json.json(channels.list(1,20,null,null,null));
        assertFalse(publicJson.contains("shared-platform-key"));assertFalse(publicJson.contains("secret_ciphertext"));assertFalse(publicJson.contains("credential_id"));
        assertEquals(0L,channels.list(1,20,null,null,"%' OR 1=1").get("total"));
        assertEquals(1L,channels.list(1,20,"DEEPSEEK","LLM","DeepSeek").get("total"));
        assertEquals(true,saved.get("modelPublished"));assertEquals(true,saved.get("rateConfigured"));
        jdbc.update("DELETE FROM ai_platform_rate");
        var unpriced=channels.save(edit("channel-without-price",2,true,null,20));assertEquals(false,unpriced.get("rateConfigured"));
        jdbc.execute("ALTER TABLE ai_model_config ALTER COLUMN is_enabled BOOLEAN");
        jdbc.execute("ALTER TABLE ai_model_config ALTER COLUMN test_status BOOLEAN");
        assertEquals(true,channels.save(edit("channel-jdbc-boolean-flags",3,true,null,20)).get("modelPublished"));
    }
    @Test void concurrentCallLimitIsSharedAndRejectedCallsDoNotHoldAllowance() {
        channels.save(edit("channel-single-flight",1,true,null,1));
        var first=admission.admit(request("team-a","single-flight-first",1000));
        RenException rejected=assertThrows(RenException.class,()->admission.admit(request("team-b","single-flight-second",1000)));
        assertEquals("CHANNEL_CONCURRENCY_LIMIT",rejected.getMsg());assertEquals(0,new BigDecimal("1").compareTo(balance("reserved",1)));
        admission.release(permit(first));assertNotNull(admission.admit(request("team-b","single-flight-third",1000)));
    }
    @Test void existingLegacyReservationStillDispatchesAndSettlesUsingItsOriginalKey() {
        var input=request("personal","legacy-in-flight",1000);var first=admission.admit(input);var permit=permit(first);
        Map<String,Object> route=new LinkedHashMap<>((Map<String,Object>)first.get("route"));
        for(String key:List.of("channelId","channelVersion","provider","accountRef","region","projectRef","credentialId","credentialVersion"))route.remove(key);
        var credential=credentials.active(1,"DEEPSEEK");
        jdbc.update("UPDATE ai_platform_reservation SET route_json=?,channel_id=NULL,credential_id=?,credential_version=? WHERE id=?",json.json(route),credential.get("id"),credential.get("version"),permit.reservationId());
        assertEquals(Map.of("api_key","not-a-real-key-1"),admission.admit(input).get("credentials"));assertEquals(true,admission.start(permit).get("started"));
        credentials.revoke(credential.get("id").toString());assertEquals("SETTLED",admission.settle(usage(permit,500)).get("state"));
    }
    @Test void legacySpeechKeyCannotBecomeLlmChannelAndNoSecretAppearsInInputString() {
        var legacy=credentials.add(new CredentialInput("VOLCENGINE","only-speech-legacy",Map.of("speech_api_key","speech-test-secret")));
        assertThrows(RenException.class,()->channels.save(new PlatformChannelService.Input("bad-legacy-import",null,null,"Bad","model","account",null,null,true,20,null,legacy.get("id").toString())));
        assertFalse(edit("redacted-input-check",1,true,Map.of("api_key","do-not-print"),20).toString().contains("do-not-print"));
    }

    @Test void concurrentDifferentPayersCannotExceedTheSharedChannelLimit() throws Exception {
        jdbc.update("INSERT INTO sys_workspace VALUES('second-personal',2,1,1)");jdbc.update("INSERT INTO sys_workspace_member VALUES('second-personal',2,1)");
        responsibilities.ensureWorkspace("second-personal");accounts.grant(new Grant("concurrency-second-grant",2L,"10","fixture"),"operator");
        channels.save(edit("cross-payer-single-flight",1,true,null,1));
        var start=new CountDownLatch(1);List<Future<Boolean>> calls=new ArrayList<>();
        try(var workers=Executors.newFixedThreadPool(2)) {
            for(String workspace:List.of("personal","second-personal"))calls.add(workers.submit(()->{start.await();try{admission.admit(request(workspace,"channel-concurrent-"+workspace,1000));return true;}catch(RenException rejected){assertEquals("CHANNEL_CONCURRENCY_LIMIT",rejected.getMsg());return false;}}));
            start.countDown();int granted=0;for(var call:calls)if(call.get(10,TimeUnit.SECONDS))granted++;assertEquals(1,granted);
        }
        assertEquals(0,new BigDecimal("1").compareTo(balance("reserved",1).add(balance("reserved",2))));
    }
    @Test void concurrentChannelRequestRetriesCommitOneVersionAndOneResult() throws Exception {
        var input=edit("concurrent-channel-rotation",1,true,Map.of("api_key","concurrent-key"),20);var start=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)) {
            var first=workers.submit(()->{start.await();return channels.save(input);});var second=workers.submit(()->{start.await();return channels.save(input);});start.countDown();
            assertEquals(first.get(10,TimeUnit.SECONDS).get("version"),second.get(10,TimeUnit.SECONDS).get("version"));
        }
        assertEquals(2L,jdbc.queryForObject("SELECT COUNT(*) FROM ai_platform_channel_credential",Long.class));
        assertEquals(2L,jdbc.queryForObject("SELECT version FROM ai_platform_channel",Long.class));
    }

}

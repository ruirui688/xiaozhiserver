package xiaozhi.modules.billing.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static xiaozhi.modules.billing.service.BillingAccountServiceTest.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.springframework.jdbc.core.JdbcTemplate;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.user.UserDetail;
import xiaozhi.modules.billing.dto.BillingCollectorHealthRequest;
import xiaozhi.modules.security.user.SecurityUser;

class BillingCollectionHealthServiceTest {
    JdbcTemplate jdbc; BillingCollectionHealthService service; BillingQueryService queries;
    BillingInternalOutbox outbox; MockedStatic<SecurityUser> security; UserDetail user;

    @BeforeEach void setup() throws Exception {
        jdbc=database();queries=mock(BillingQueryService.class);outbox=mock(BillingInternalOutbox.class);
        when(queries.workspace()).thenReturn("workspace-a");when(outbox.status()).thenReturn(Map.of("status","READY","pendingCount",0));
        service=new BillingCollectionHealthService(jdbc,new BillingSupport(new ObjectMapper().findAndRegisterModules()),queries,outbox);
        user=new UserDetail();user.setSuperAdmin(1);security=mockStatic(SecurityUser.class);security.when(SecurityUser::getUser).thenReturn(user);
    }
    @AfterEach void close(){if(security!=null)security.close();}

    @Test void absentHeartbeatOrHeartbeatAloneNeverClaimsUsageCoverage() {
        var result=service.overview(true);assertEquals("NEVER_SEEN",map(result,"python").get("status"));
        assertEquals("NO_USAGE_EVIDENCE",result.get("status"));assertEquals(false,map(result,"coverage").get("complete"));
        assertEquals(true,service.accept(heartbeat()).get("accepted"));result=service.overview(true);
        assertEquals("ALIVE",map(result,"python").get("status"));assertEquals("NO_USAGE_EVIDENCE",result.get("status"));
        assertEquals("JAVA_OUTBOX_ONLY",map(result,"javaQueue").get("scope"));
        verify(queries,never()).workspace();
    }

    @Test void unreadableQueueAndObservedLossTakePriorityOverNumericZeroAndPending() {
        var request=heartbeat();request.setQueueAvailable(false);service.accept(request);
        assertEquals("INCOMPLETE",service.overview(true).get("status"));
        request.setQueueAvailable(true);request.setPendingCount(3);service.accept(request);
        assertEquals("PENDING",service.overview(true).get("status"));
        request.setMissingContextCalls(1);service.accept(request);
        assertEquals("INCOMPLETE",service.overview(true).get("status"));
    }

    @Test void expiredHeartbeatIsNotAliveAndDoesNotInventAnActiveQueue() {
        var request=heartbeat();request.setPendingCount(9);service.accept(request);
        jdbc.update("UPDATE ai_billing_collector_health SET received_at=?",BillingSupport.time(Instant.now().minusSeconds(180)));
        var result=service.overview(true);assertEquals("STALE",map(result,"python").get("status"));
        assertEquals("NO_USAGE_EVIDENCE",result.get("status"));
        assertEquals("STALE",list(map(result,"python"),"instances").getFirst().get("heartbeatStatus"));
    }

    @Test void mysqlLocalDateTimeHeartbeatCanBeReadAndExpiresNormally() {
        var mysql=BillingJdbcTimeFixture.localDateTimes(jdbc);
        service=new BillingCollectionHealthService(mysql,new BillingSupport(new ObjectMapper().findAndRegisterModules()),queries,outbox);
        service.accept(heartbeat());
        assertInstanceOf(LocalDateTime.class,mysql.queryForMap("SELECT received_at FROM ai_billing_collector_health").get("received_at"));
        assertEquals("ALIVE",map(service.overview(true),"python").get("status"));
        jdbc.update("UPDATE ai_billing_collector_health SET received_at=?",BillingSupport.time(Instant.now().minusSeconds(180)));
        assertEquals("STALE",map(service.overview(true),"python").get("status"));
    }

    @Test void workspaceMemberOnlySeesOwnTrustedDatabaseEvidence() {
        service.accept(heartbeat());context("context-a","workspace-a");context("context-b","workspace-b");
        event("event-a","workspace-a","context-a");event("event-b","workspace-b","context-b");
        event("platform-event",null,null);user.setSuperAdmin(0);
        assertThrows(RenException.class,()->service.overview(true));
        var result=service.overview(false);assertEquals("WORKSPACE",result.get("scope"));
        assertEquals(1L,map(result,"contexts").get("count"));assertEquals(1L,map(result,"events").get("count"));
        assertEquals("COLLECTED_RECORDS_ONLY",result.get("status"));
        assertEquals("WORKSPACE_EVIDENCE_ONLY",map(result,"python").get("status"));
        assertEquals(List.of(),map(result,"python").get("instances"));assertNull(result.get("javaQueue"));
        verify(outbox,never()).status();verify(queries).workspace();
    }

    @Test void futureTimestampsAndInvalidHealthFieldsAreRejected() {
        var future=heartbeat();future.setStartedAt(Instant.now().plusSeconds(600));
        assertThrows(RenException.class,()->service.accept(future));
        var request=heartbeat();request.setSupportedAdapters(List.of("LLM/openai","ASR/doubao_stream","REALTIME/doubao-realtime"));
        try(var factory=Validation.buildDefaultValidatorFactory()) {
            assertTrue(factory.getValidator().validate(request).isEmpty());
            request.setRejectedCount(-1);request.setInstanceId("unsafe/instance");request.setLastDeliveryErrorCode("raw private exception");
            assertEquals(3,factory.getValidator().validate(request).size());
        }
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM ai_billing_collector_health",Integer.class));
    }

    private BillingCollectorHealthRequest heartbeat(){var r=new BillingCollectorHealthRequest();r.setInstanceId("instance-0001");r.setStartedAt(Instant.now().minusSeconds(60));r.setCollectorVersion("1");r.setQueueAvailable(true);r.setManagedMode(true);return r;}
    private void context(String id,String workspace){jdbc.update("INSERT INTO ai_billing_context(id,token_hash,workspace_id,purpose,snapshot_json,issued_at) VALUES (?,?,?,'CONVERSATION','{}',?)",id,BillingSupport.hash(id),workspace,BillingSupport.time(Instant.now()));}
    private void event(String id,String workspace,String context){jdbc.update("INSERT INTO ai_billing_usage_event(event_id,context_id,payload_hash,workspace_id,model_id,provider,service_type,purpose,started_at,ended_at,status,received_at) VALUES (?,?,?,?,'model','DeepSeek','LLM','CONVERSATION',?,?,'SUCCESS',?)",id,context,BillingSupport.hash(id),workspace,BillingSupport.time(Instant.now()),BillingSupport.time(Instant.now()),BillingSupport.time(Instant.now()));}
}

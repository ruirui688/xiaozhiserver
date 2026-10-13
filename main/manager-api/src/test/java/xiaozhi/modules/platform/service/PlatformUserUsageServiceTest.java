package xiaozhi.modules.platform.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.billing.dto.BillingFilter;
import xiaozhi.modules.workspace.service.WorkspacePermissionService;

public class PlatformUserUsageServiceTest {
    JdbcTemplate jdbc,admin;String database;PlatformUserUsageService service;WorkspacePermissionService permissions;
    @BeforeEach void setup() {
        String url=System.getenv("PLATFORM_USAGE_MYSQL_URL");
        if(url!=null) {
            String user=System.getenv("PLATFORM_USAGE_MYSQL_USER"),password=System.getenv("PLATFORM_USAGE_MYSQL_PASSWORD");
            admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));database="platform_usage_test_"+UUID.randomUUID().toString().replace("-","");
            admin.execute("CREATE DATABASE "+database+" CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            jdbc=new JdbcTemplate(new DriverManagerDataSource(url.replace("?","/"+database+"?"),user,password));
        } else {
            jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:usage"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa",""));
            jdbc.execute("CREATE ALIAS JSON_EXTRACT FOR 'xiaozhi.modules.platform.service.PlatformUserUsageServiceTest.jsonExtract'");
            jdbc.execute("CREATE ALIAS JSON_UNQUOTE FOR 'xiaozhi.modules.platform.service.PlatformUserUsageServiceTest.jsonUnquote'");
        }
        jdbc.execute("CREATE TABLE ai_agent(id varchar(64),workspace_id varchar(64),agent_name varchar(255))");
        jdbc.execute("CREATE TABLE ai_device(id varchar(64),workspace_id varchar(64),alias varchar(255),board varchar(128))");
        jdbc.execute("CREATE TABLE ai_model_config(id varchar(64),model_name varchar(255))");
        jdbc.execute("CREATE TABLE ai_platform_reservation(id varchar(64) PRIMARY KEY,workspace_id varchar(64),model_id varchar(64),service_type varchar(32),route_json text,state varchar(20),settled_amount decimal(30,9),reserved_amount decimal(30,9),created_at datetime(3),started_at datetime(3),settled_at datetime(3))");
        jdbc.execute("CREATE TABLE ai_billing_usage_event(event_id varchar(64) PRIMARY KEY,reservation_id varchar(64),workspace_id varchar(64),agent_id varchar(64),agent_name varchar(255),device_id varchar(64),device_name varchar(255),device_board varchar(128),model_id varchar(64),model_name varchar(255),provider varchar(64),service_type varchar(32),purpose varchar(64),session_id varchar(128),turn_id varchar(128),started_at datetime(3),ended_at datetime(3),status varchar(16),usage_complete int)");
        jdbc.execute("CREATE TABLE ai_billing_usage_metric(event_id varchar(64),metric varchar(64),unit varchar(32),source varchar(16),quantity decimal(30,9))");
        jdbc.update("INSERT INTO ai_agent VALUES('agent-a','workspace-a','Test agent'),('agent-b','workspace-b','Private agent')");
        jdbc.update("INSERT INTO ai_device VALUES('device-a','workspace-a','Test device','test-board')");
        jdbc.update("INSERT INTO ai_model_config VALUES('model','Model name')");
        permissions=mock(WorkspacePermissionService.class);when(permissions.currentWorkspaceId()).thenReturn("workspace-a");service=new PlatformUserUsageService(jdbc,permissions);
    }
    @AfterEach void cleanup() { if(admin!=null&&database!=null)admin.execute("DROP DATABASE "+database); }
    public static String jsonExtract(String json,String path) throws Exception {return new ObjectMapper().writeValueAsString(new ObjectMapper().readTree(json).path(path.substring(2)));}
    public static String jsonUnquote(String json) throws Exception {return json==null?null:new ObjectMapper().readTree(json).asText();}
    BillingFilter filter() {var f=new BillingFilter();f.setStartDate("2026-10-12");f.setEndDate("2026-10-12");return f;}
    void reservation(String id,String workspace,String state,String amount,String hold) {
        jdbc.update("INSERT INTO ai_platform_reservation VALUES(?,?, 'model','LLM',?,?,?,?, '2026-10-12 02:00:00',NULL,NULL)",id,workspace,
            "{\"agentId\":\"agent-a\",\"deviceId\":\"device-a\",\"provider\":\"DEEPSEEK\",\"api_key\":\"never-return-this\"}",state,amount==null?null:new BigDecimal(amount),new BigDecimal(hold));
    }
    void event(String id,String reservation,String workspace) {
        jdbc.update("INSERT INTO ai_billing_usage_event VALUES(?,?,?,'agent-a','Recorded agent','device-a','Recorded device','test-board','model','Historical model','deepseek','LLM','CONVERSATION','session-a','turn-a','2026-10-12 02:00:00','2026-10-12 02:00:01','SUCCESS',1)",id,reservation,workspace);
        jdbc.update("INSERT INTO ai_billing_usage_metric VALUES(?,'input_tokens','token','PROVIDER',100)",id);
    }
    @SuppressWarnings("unchecked") List<Map<String,Object>> rows(Map<String,Object> result){return (List<Map<String,Object>>)result.get("list");}
    @SuppressWarnings("unchecked") Map<String,Object> settlement(Map<String,Object> result){return (Map<String,Object>)result.get("platformSettlement");}
    void amount(String expected,Object actual){assertEquals(0,new BigDecimal(expected).compareTo(new BigDecimal(actual.toString())));}

    @Test void chargesCountEachReservationOnceDespiteMultipleTelemetryRows() {
        reservation("settled","workspace-a","SETTLED","2.125","5");event("event-a","settled","workspace-a");event("event-b","settled","workspace-a");
        var overview=service.overview(filter());amount("2.125",settlement(overview).get("settledAmount"));amount("0",settlement(overview).get("reservedAmount"));amount("1",settlement(overview).get("settledCount"));
        var events=service.events(filter());assertEquals("workspace-a",events.get("workspaceId"));assertEquals("WORKSPACE",events.get("scope"));assertEquals(2,rows(events).size());
        assertTrue(rows(events).stream().allMatch(row->Boolean.TRUE.equals(row.get("settlementShared"))));
        var single=filter();single.setLimit(1);assertEquals(true,rows(service.events(single)).getFirst().get("settlementShared"));
        var grouped=service.groups(filter(),"agent");assertEquals("workspace-a",grouped.get("workspaceId"));assertEquals("WORKSPACE",grouped.get("scope"));
        var groups=rows(grouped);assertEquals(1,groups.size());amount("2.125",settlement(groups.getFirst()).get("settledAmount"));
    }
    @Test void reservationWithoutTelemetryIsVisibleAndNeverInventsUsageOrSuccess() {
        reservation("waiting","workspace-a","UNKNOWN",null,"3.000001");
        var row=rows(service.events(filter())).getFirst();assertEquals("RESERVATION",row.get("recordType"));assertEquals("UNKNOWN",row.get("status"));assertEquals("UNKNOWN",row.get("settlementStatus"));assertEquals(false,row.get("usageComplete"));assertEquals(List.of(),row.get("usage"));assertNull(row.get("settledAmount"));amount("3.000001",row.get("reservedAmount"));assertEquals("Model name",row.get("modelName"));
        assertEquals(false,row.get("settlementShared"));assertEquals("2026-10-12T02:00:00Z",row.get("startedAt"));amount("1",settlement(service.overview(filter())).get("reviewCount"));
    }
    @Test void sharedWorkspaceCallsRemainVisibleInAgentAndDeviceGroupsAndPagination() {
        reservation("workspace-task","workspace-a","SETTLED","1.25","2");
        jdbc.update("UPDATE ai_platform_reservation SET route_json='{\"provider\":\"DEEPSEEK\"}' WHERE id='workspace-task'");
        reservation("device-task","workspace-a","SETTLED","0.75","1");event("device-event","device-task","workspace-a");
        for(String groupBy:List.of("agent","device")) {
            var page=filter();page.setLimit(1);var first=service.groups(page,groupBy);
            amount("2",first.get("total"));var shared=rows(first).getFirst();assertNull(shared.get("id"));assertEquals("空间公共任务",shared.get("name"));
            amount("1",shared.get("callCount"));amount("1.25",settlement(shared).get("settledAmount"));
            page.setPage(2);var device=rows(service.groups(page,groupBy)).getFirst();assertNotNull(device.get("id"));amount("0.75",settlement(device).get("settledAmount"));
        }
        amount("2",settlement(service.overview(filter())).get("settledAmount"));
    }
    @Test void unlinkedHistoricalRecordsHaveUnknownChargesAndDoNotUseSupplierInvoices() {
        event("historical",null,"workspace-a");
        var row=rows(service.events(filter())).getFirst();assertEquals("UNTRACKED",row.get("settlementStatus"));assertNull(row.get("settledAmount"));assertNull(row.get("reservedAmount"));assertEquals(false,row.get("settlementShared"));
        amount("1",settlement(service.overview(filter())).get("unlinkedCount"));
        // The fixture intentionally has no invoice, supplier account, sync or balance tables.
        assertNotNull(service.options().get("models"));assertNotNull(service.groups(filter(),"device").get("list"));
    }
    @Test void releasedUndispatchedReservationsHaveKnownZeroChargesWhileUnknownUsageStaysNull() {
        for(String state:List.of("RELEASED","EXPIRED","CANCELLED"))reservation(state,"workspace-a",state,null,"2");
        reservation("uncertain","workspace-a","UNKNOWN",null,"3");
        var all=rows(service.events(filter()));assertEquals(4,all.size());
        for(var row:all) {
            if("UNKNOWN".equals(row.get("settlementStatus"))) {assertNull(row.get("settledAmount"));amount("3",row.get("reservedAmount"));}
            else {amount("0",row.get("settledAmount"));amount("0",row.get("reservedAmount"));}
        }
        var totals=settlement(service.overview(filter()));amount("0",totals.get("settledAmount"));amount("3",totals.get("reservedAmount"));amount("1",totals.get("reviewCount"));
    }
    @Test void crossWorkspaceOrModelAssociationsCannotRevealAnotherPayersSettlement() {
        reservation("private","workspace-b","SETTLED","777","800");event("spoofed","private","workspace-a");
        reservation("mismatched","workspace-a","SETTLED","4","5");event("wrong-model","mismatched","workspace-a");jdbc.update("UPDATE ai_billing_usage_event SET model_id='other' WHERE event_id='wrong-model'");
        var all=rows(service.events(filter()));assertEquals(3,all.size());
        assertEquals("UNTRACKED",all.stream().filter(row->"spoofed".equals(row.get("eventId"))).findFirst().orElseThrow().get("settlementStatus"));
        assertEquals("UNTRACKED",all.stream().filter(row->"wrong-model".equals(row.get("eventId"))).findFirst().orElseThrow().get("settlementStatus"));
        amount("4",settlement(service.overview(filter())).get("settledAmount"));
        String publicJson=all.toString()+service.options();assertFalse(publicJson.contains("777"));assertFalse(publicJson.contains("never-return-this"));assertFalse(publicJson.contains("route_json"));assertFalse(publicJson.contains("credential"));
    }
    @Test void filteringAndPaginationApplyToBothSourcesBeforeCounting() {
        reservation("only-reservation","workspace-a","STARTED",null,"1");event("only-event",null,"workspace-a");event("other-workspace",null,"workspace-b");
        var f=filter();f.setLimit(1);f.setPage(2);amount("2",service.events(f).get("total"));assertEquals(1,rows(service.events(f)).size());
        f.setPage(1);f.setAgentId("unknown-agent");assertTrue(rows(service.events(f)).isEmpty());
        f.setAgentId("agent-a");f.setDeviceId("device-a");f.setServiceType("LLM");f.setModelId("model");f.setBoard("test-board");amount("2",service.events(f).get("total"));
        f.setKeyword("%");assertTrue(rows(service.events(f)).isEmpty());
    }
    @Test void dateBoundsUseShanghaiDaysAgainstUtcStoredTimes() {
        event("at-start",null,"workspace-a");event("before-start",null,"workspace-a");event("at-end",null,"workspace-a");
        jdbc.update("UPDATE ai_billing_usage_event SET started_at='2026-10-11 16:00:00' WHERE event_id='at-start'");jdbc.update("UPDATE ai_billing_usage_event SET started_at='2026-10-11 15:59:59.999' WHERE event_id='before-start'");jdbc.update("UPDATE ai_billing_usage_event SET started_at='2026-10-12 16:00:00' WHERE event_id='at-end'");
        var result=rows(service.events(filter()));assertEquals(1,result.size());assertEquals("at-start",result.getFirst().get("eventId"));
    }
    @Test void missingCurrentNamesFallBackAndAuthorizationRunsBeforeDatabaseReads() {
        reservation("removed-model","workspace-a","RESERVED",null,"1");jdbc.update("DELETE FROM ai_model_config");assertEquals("model",rows(service.events(filter())).getFirst().get("modelName"));
        doThrow(new RenException("DENIED")).when(permissions).requireRead("workspace-a");
        assertThrows(RenException.class,()->service.options());assertThrows(RenException.class,()->service.overview(filter()));assertThrows(RenException.class,()->service.groups(filter(),"agent"));assertThrows(RenException.class,()->service.events(filter()));
    }
    @Test void emptyDataIsNeutralAndRejectsInvalidFilters() {
        amount("0",service.overview(filter()).get("callCount"));amount("0",settlement(service.overview(filter())).get("settledAmount"));assertTrue(rows(service.events(filter())).isEmpty());
        var bad=filter();bad.setLimit(101);assertThrows(RenException.class,()->service.events(bad));assertThrows(RenException.class,()->service.groups(filter(),"unsupported"));
    }
}

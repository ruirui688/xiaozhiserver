package xiaozhi.modules.billing.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.user.UserDetail;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.billing.dto.*;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.model.dao.ModelConfigDao;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.modules.workspace.service.WorkspacePermissionService;

class BillingLedgerTest {
    JdbcTemplate jdbc; BillingContextService contexts; BillingUsageService usage; BillingQueryService queries;
    BillingBindingService bindings; BillingBillService bills; TransactionTemplate tx; ObjectMapper mapper;
    WorkspacePermissionService permissions; MockedStatic<SecurityUser> security;

    @BeforeEach void setUp() throws Exception {
        JdbcDataSource ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        String schema=Files.readString(Path.of("src/main/resources/db/changelog/model-billing-management.sql"))
                .replaceAll("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","").replace("scope_value(128)","scope_value").replace("bill_id(128)","bill_id");
        for(String sql:schema.split(";")) if(!sql.isBlank())jdbc.execute(sql);
        String openExpenseEnd=Files.readString(Path.of("src/main/resources/db/changelog/model-billing-open-expense-end.sql"));
        for(String sql:openExpenseEnd.split(";")) if(!sql.isBlank())jdbc.execute(sql);
        jdbc.execute("CREATE TABLE ai_agent(id VARCHAR(64),workspace_id VARCHAR(64),agent_name VARCHAR(255))");
        jdbc.execute("CREATE TABLE ai_device(id VARCHAR(64),workspace_id VARCHAR(64),alias VARCHAR(255),board VARCHAR(128))");
        jdbc.update("INSERT INTO ai_agent VALUES ('agent-a','workspace-a','Agent A'),('agent-b','workspace-b','Agent B')");
        jdbc.update("INSERT INTO ai_device VALUES ('device-a','workspace-a','Device A','board-a')");
        mapper=new ObjectMapper().findAndRegisterModules();BillingSupport support=new BillingSupport(mapper);
        ModelConfigDao modelDao=mock(ModelConfigDao.class);ModelConfigEntity model=new ModelConfigEntity();
        model.setId("model-a");model.setModelType("LLM");model.setVendorName("DeepSeek");model.setModelName("Direct DeepSeek");
        when(modelDao.selectById("model-a")).thenReturn(model);
        contexts=new BillingContextService(jdbc,support,modelDao);
        var outbox=mock(BillingInternalOutbox.class);when(outbox.status()).thenReturn(Map.of("status","READY"));
        usage=new BillingUsageService(jdbc,support,contexts,tx,Validation.buildDefaultValidatorFactory().getValidator(),outbox);
        permissions=mock(WorkspacePermissionService.class);when(permissions.currentWorkspaceId()).thenReturn("workspace-a");
        queries=new BillingQueryService(jdbc,permissions,mock(VolcengineBillingClient.class),outbox,support);
        bindings=new BillingBindingService(jdbc,queries);bills=new BillingBillService(jdbc,support,bindings);
        UserDetail user=new UserDetail();user.setId(1L);user.setSuperAdmin(1);
        security=mockStatic(SecurityUser.class);security.when(SecurityUser::getUser).thenReturn(user);security.when(SecurityUser::getUserId).thenReturn(1L);
    }
    @AfterEach void close(){security.close();}

    @Test void rejectsForgedContextAndKeepsOriginalScopeAfterDeviceDeletion() {
        Map<String,Object> context=deviceContext();UsageEventRequest event=event(context,"event-late-0001");
        event.setContextToken("forged");assertEquals("INVALID_CONTEXT",rejection(accept(event)));
        event.setContextToken((String)context.get("context_token"));
        jdbc.update("DELETE FROM ai_device WHERE id='device-a'");jdbc.update("DELETE FROM ai_agent WHERE id='agent-a'");
        assertEquals(List.of("event-late-0001"),accept(event).get("accepted"));
        assertEquals("workspace-a",jdbc.queryForObject("SELECT workspace_id FROM ai_billing_usage_event",String.class));
        assertEquals("agent-a",jdbc.queryForObject("SELECT agent_id FROM ai_billing_usage_event",String.class));
        verify(permissions,never()).requireRead("workspace-b");
    }

    @Test void exactRetryIsAcceptedOnceButChangedEventIdPayloadIsRejected() {
        UsageEventRequest event=event(deviceContext(),"event-retry-0001");
        assertEquals(1,((List<?>)accept(event).get("accepted")).size());
        assertEquals(1,((List<?>)accept(event).get("accepted")).size());
        event.setStatus("FAILED");assertEquals("EVENT_ID_CONFLICT",rejection(accept(event)));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM ai_billing_usage_event",Integer.class));
        assertEquals(new BigDecimal("123.000000000"),jdbc.queryForObject("SELECT quantity FROM ai_billing_usage_metric",BigDecimal.class));
    }

    @Test void independentLedgerCommitSurvivesCallingBusinessTransactionRollback() {
        UsageEventRequest event=event(deviceContext(),"event-tx-00001");
        tx.executeWithoutResult(status->{accept(event);status.setRollbackOnly();});
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM ai_billing_usage_event",Integer.class));
    }

    @Test void modelOutsideCapabilityAndServiceSpoofingAreRejected() {
        var event=event(deviceContext(),"event-model-0001");event.setModelId("other-model");
        assertEquals("MODEL_OUTSIDE_CONTEXT",rejection(accept(event)));event.setModelId("model-a");event.setServiceType("TTS");
        assertEquals("MODEL_SERVICE_MISMATCH",rejection(accept(event)));
    }

    @Test void platformUsageIsNotVisibleInAnArbitrarySelectedWorkspace() {
        var event=event(contexts.issuePlatform(List.of("model-a"),"BENCHMARK"),"event-platform-0001");
        accept(event);assertNull(jdbc.queryForObject("SELECT workspace_id FROM ai_billing_usage_event",String.class));
        assertEquals("BENCHMARK",jdbc.queryForObject("SELECT purpose FROM ai_billing_usage_event",String.class));
        assertEquals(0,queries.events(filter()).getTotal());
    }

    @Test void workspaceFilterCannotExposeOtherWorkspaceEventsAndEmptyUsageStaysUnknown() {
        var event=event(deviceContext(),"event-scope-0001");event.setUsage(List.of());event.setUsageComplete(false);accept(event);
        var page=queries.events(filter());assertEquals(1,page.getTotal());assertEquals(false,page.getList().getFirst().get("usageComplete"));
        assertEquals(List.of(),page.getList().getFirst().get("usage"));
        when(permissions.currentWorkspaceId()).thenReturn("workspace-b");
        BillingFilter filter=filter();filter.setAgentId("agent-a");assertEquals(0,queries.events(filter).getTotal());
    }

    @Test void effectiveBindingRejectsCrossWorkspaceAndOverlapAndCanEndAfterConsumedPeriod() {
        var binding=binding();binding.setAgentId("agent-b");assertThrows(RenException.class,()->bindings.save(binding));
        binding.setAgentId("agent-a");var saved=tx.execute(status->bindings.save(binding));
        assertThrows(RenException.class,()->tx.execute(status->bindings.save(binding())));
        var row=bill("BILL","bill-bound",null,"10.20");bills.save(List.of(row));
        String id=saved.get("id").toString();binding.setId(id);binding.setValidTo(Instant.parse("2026-10-02T00:00:00Z"));
        assertEquals("RETIRED",tx.execute(status->bindings.save(binding)).get("status"));
        binding.setValidTo(Instant.parse("2026-10-01T00:30:00Z"));assertThrows(RenException.class,()->bindings.save(binding));
        assertThrows(RenException.class,()->bindings.delete(id));
    }

    @Test void mysqlLocalDateTimeContextStillEnforcesCallTimeAndReferencedBindingHistory() {
        var mysql=BillingJdbcTimeFixture.localDateTimes(jdbc);var context=deviceContext();
        var mysqlContexts=new BillingContextService(mysql,new BillingSupport(mapper),mock(ModelConfigDao.class));
        String id=context.get("context_id").toString(),token=context.get("context_token").toString();
        Instant issuedAt=Instant.parse("2026-10-01T00:00:00Z");
        jdbc.update("UPDATE ai_billing_context SET issued_at=? WHERE id=?",BillingSupport.time(issuedAt),id);
        assertInstanceOf(LocalDateTime.class,mysql.queryForMap("SELECT issued_at FROM ai_billing_context WHERE id=?",id).get("issued_at"));
        assertEquals("workspace-a",mysqlContexts.verify(id,token,"model-a",issuedAt).get("workspace_id"));
        assertThrows(RenException.class,()->mysqlContexts.verify(id,token,"model-a",issuedAt.minusSeconds(301)));
        assertThrows(RenException.class,()->mysqlContexts.verify(id,"forged","model-a",issuedAt));

        var request=binding();var saved=tx.execute(status->bindings.save(request));
        bills.save(List.of(bill("BILL","mysql-time-bill",null,"10.20")));
        var mysqlBindings=new BillingBindingService(mysql,queries);
        request.setId(saved.get("id").toString());request.setValidTo(Instant.parse("2026-10-03T00:00:00Z"));
        assertEquals("RETIRED",tx.execute(status->mysqlBindings.save(request)).get("status"));
        request.setValidTo(Instant.parse("2026-10-02T00:00:00Z"));
        assertEquals("RETIRED",tx.execute(status->mysqlBindings.save(request)).get("status"));
        request.setValidTo(Instant.parse("2026-10-04T00:00:00Z"));
        assertThrows(RenException.class,()->tx.execute(status->mysqlBindings.save(request)));
        request.setValidTo(Instant.parse("2026-10-01T00:30:00Z"));
        assertThrows(RenException.class,()->tx.execute(status->mysqlBindings.save(request)));
    }

    @Test void partialImportedDetailsDoNotReplaceAParentBillAndCompleteSnapshotDoes() {
        tx.executeWithoutResult(status->bindings.save(binding()));
        bills.save(List.of(bill("BILL","bill-parent",null,"20.00"),bill("DETAIL","bill-parent","partial-detail","5.00")));
        var rows=jdbc.queryForList("SELECT b.level,b.payable_amount FROM ai_billing_bill b WHERE 1=1"+BillingQueryService.CANONICAL_BILL);
        assertEquals(1,rows.size());assertEquals("BILL",rows.getFirst().get("level"));
        var parent=bill("BILL","bill-parent",null,"20.00");var detail=bill("DETAIL","bill-parent","complete-detail","20.00");
        parent.put("source","VOLCENGINE_API");detail.put("source","VOLCENGINE_API");
        // Existing import is intentionally replaced by an explicit full official snapshot with same IDs.
        jdbc.update("DELETE FROM ai_billing_bill");
        tx.executeWithoutResult(status->bills.replacePeriod("VOLCENGINE","account-a","2026-10",List.of(parent,detail)));
        rows=jdbc.queryForList("SELECT b.level,b.payable_amount FROM ai_billing_bill b WHERE 1=1"+BillingQueryService.CANONICAL_BILL);
        assertEquals(1,rows.size());assertEquals("DETAIL",rows.getFirst().get("level"));
        tx.executeWithoutResult(status->bills.replacePeriod("VOLCENGINE","account-a","2026-10",List.of(bill("BILL","revised",null,"-2.5"))));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM ai_billing_bill",Integer.class));
    }

    @Test void unattributedBillsAreAdminOnlyAndWorkspaceAmountIsNotCopiedToDevice() {
        var binding=binding();binding.setAgentId(null);tx.executeWithoutResult(status->bindings.save(binding));
        bills.save(List.of(bill("BILL","bill-workspace",null,"5.0")));
        BillingFilter filtered=filter();filtered.setDeviceId("device-a");assertEquals(0,queries.bills(filtered).getTotal());
        assertEquals(1,queries.bills(filter()).getTotal());
        UserDetail member=new UserDetail();member.setSuperAdmin(0);security.when(SecurityUser::getUser).thenReturn(member);
        filtered.setUnattributed(true);assertThrows(RenException.class,()->queries.bills(filtered));
    }

    @Test void officialOpenExpenseEndRemainsUnassignedUntilTheSupplierCompletesItsPeriod() {
        var accountBinding=binding();accountBinding.setScopeType("ACCOUNT");accountBinding.setScopeValue("*");
        assertNull(accountBinding.getValidTo());
        var savedBinding=tx.execute(status->bindings.save(accountBinding));
        var context=deviceContext();
        jdbc.update("UPDATE ai_billing_context SET issued_at=? WHERE id=?",LocalDateTime.parse("2026-10-01T00:00:00"),context.get("context_id"));
        var usageEvent=event(context,"event-open-end-0001");
        usageEvent.setStartedAt(Instant.parse("2026-10-01T00:15:00Z"));
        usageEvent.setEndedAt(Instant.parse("2026-10-01T00:15:01Z"));
        assertEquals(List.of("event-open-end-0001"),accept(usageEvent).get("accepted"));

        var pending=officialRtcBill("");String id=pending.get("id").toString();
        int imported=tx.execute(status->bills.replacePeriod("VOLCENGINE","account-a","2026-10",List.of(pending)));assertEquals(1,imported);
        var stored=jdbc.queryForMap("SELECT * FROM ai_billing_bill WHERE id=?",id);
        assertNull(stored.get("expense_end"));assertEquals("UNASSIGNED",stored.get("attribution_level"));
        for(String field:List.of("workspace_id","agent_id","device_id","binding_id"))assertNull(stored.get(field),field);

        BillingFilter pendingFilter=filter();pendingFilter.setUnattributed(true);
        var pendingPage=queries.bills(pendingFilter);assertEquals(1,pendingPage.getTotal());
        var visible=pendingPage.getList().getFirst();assertEquals(id,visible.get("id"));
        assertTrue(visible.containsKey("expenseEnd"));assertNull(visible.get("expenseEnd"));
        assertEquals(new BigDecimal("20.123456789"),visible.get("payableAmount"));
        assertEquals(new BigDecimal("12.500000000"),visible.get("paidAmount"));
        assertEquals("",((Map<?,?>)visible.get("supplierUsage")).get("ExpenseEndTime"));
        assertEquals(0,queries.bills(filter()).getTotal());
        var groups=queries.groups(filter(),"agent");assertEquals(1,groups.getTotal());
        var group=groups.getList().getFirst();assertEquals(1L,group.get("callCount"));
        assertEquals(List.of(),group.get("costs"));assertEquals("NO_ATTRIBUTABLE_BILLS",group.get("costStatus"));

        BillingFilter afterBegin=filter();afterBegin.setStartDate("2026-10-02");afterBegin.setUnattributed(true);
        assertEquals(0,queries.bills(afterBegin).getTotal());
        BillingFilter beforeBegin=filter();beforeBegin.setStartDate("2026-09-01");beforeBegin.setEndDate("2026-09-30");beforeBegin.setUnattributed(true);
        assertEquals(0,queries.bills(beforeBegin).getTotal());

        UserDetail admin=SecurityUser.getUser();UserDetail member=new UserDetail();member.setSuperAdmin(0);
        security.when(SecurityUser::getUser).thenReturn(member);
        assertThrows(RenException.class,()->queries.bills(pendingFilter));
        security.when(SecurityUser::getUser).thenReturn(admin);

        var completed=officialRtcBill("2026-10-01 09:00:00");assertEquals(id,completed.get("id"));
        imported=tx.execute(status->bills.replacePeriod("VOLCENGINE","account-a","2026-10",List.of(completed)));assertEquals(1,imported);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM ai_billing_bill",Integer.class));
        stored=jdbc.queryForMap("SELECT * FROM ai_billing_bill WHERE id=?",id);
        assertEquals("workspace-a",stored.get("workspace_id"));assertEquals("agent-a",stored.get("agent_id"));
        assertEquals(savedBinding.get("id"),stored.get("binding_id"));assertEquals("AGENT",stored.get("attribution_level"));
        assertEquals(LocalDateTime.parse("2026-10-01T01:00:00"),((java.sql.Timestamp)stored.get("expense_end")).toLocalDateTime());
        assertEquals(0,queries.bills(pendingFilter).getTotal());
        var attributed=queries.bills(filter());assertEquals(1,attributed.getTotal());assertEquals(id,attributed.getList().getFirst().get("id"));
        var costs=(List<Map<String,Object>>)queries.groups(filter(),"agent").getList().getFirst().get("costs");
        assertEquals(1,costs.size());assertEquals(new BigDecimal("20.123456789"),costs.getFirst().get("payableAmount"));
        assertEquals(new BigDecimal("12.500000000"),costs.getFirst().get("paidAmount"));
    }

    @Test void groupsAggregateDecimalsWithoutConvertingUnknownPaidAmountsToZero() {
        accept(event(deviceContext(),"event-groups-0001"));
        tx.executeWithoutResult(status->bindings.save(binding()));
        bills.save(List.of(bill("BILL","bill-group",null,"0.123456789")));
        var groups=queries.groups(filter(),"agent");assertEquals(1,groups.getTotal());
        var row=groups.getList().getFirst();assertEquals(1L,row.get("callCount"));
        var costs=(List<Map<String,Object>>)row.get("costs");
        assertEquals(new BigDecimal("0.123456789"),costs.getFirst().get("payableAmount"));
        assertNull(costs.getFirst().get("paidAmount"));
    }

    @Test void purposeAndModelFiltersCannotMisrepresentUnsupportedOfficialCosts() {
        accept(event(deviceContext(),"event-filter-0001"));
        tx.executeWithoutResult(status->bindings.save(binding()));
        bills.save(List.of(bill("BILL","bill-filter",null,"5.0")));
        var filtered=filter();filtered.setPurpose("CONVERSATION");
        var group=queries.groups(filtered,"agent").getList().getFirst();
        assertEquals("UNSUPPORTED_FILTER",group.get("costStatus"));assertEquals(List.of(),group.get("costs"));
        assertThrows(RenException.class,()->queries.bills(filtered));
        filtered.setPurpose("BENCHMARK");assertEquals(0,queries.events(filtered).getTotal());
        assertEquals(0,queries.groups(filtered,"agent").getTotal());
        filtered.setPurpose(null);filtered.setModelId("model-a");
        assertEquals("UNSUPPORTED_FILTER",queries.groups(filtered,"agent").getList().getFirst().get("costStatus"));
        assertThrows(RenException.class,()->queries.bills(filtered));
    }

    @Test void unassignedKeywordSearchUsesSupplierFieldsAndLayerExpansionPreservesParentId() {
        bills.save(List.of(bill("BILL","find-me",null,"5.0"),bill("DETAIL","find-me","detail-1","5.0")));
        var filtered=filter();filtered.setUnattributed(true);filtered.setKeyword("find-me");filtered.setLevel("BILL");
        assertEquals(1,queries.bills(filtered).getTotal());
        filtered.setLevel("DETAIL");filtered.setBillId("find-me");assertEquals(1,queries.bills(filtered).getTotal());
        filtered.setAccountRef("other-account");assertEquals(0,queries.bills(filtered).getTotal());
        filtered.setAccountRef("account-a");assertEquals(1,queries.bills(filtered).getTotal());
        filtered.setBillId("different");assertEquals(0,queries.bills(filtered).getTotal());
        filtered.setDeviceId("device-a");assertThrows(RenException.class,()->queries.bills(filtered));
    }

    @Test void completeDetailsUseTheirPayableWhileParentCashIsCountedOnlyOnce() {
        tx.executeWithoutResult(status->bindings.save(binding()));
        var parent=bill("BILL","bill-cash",null,"20");parent.put("paid_amount",new BigDecimal("3"));
        var detail=bill("DETAIL","bill-cash","detail-cash","20");
        tx.executeWithoutResult(status->bills.replacePeriod("VOLCENGINE","account-a","2026-10",List.of(parent,detail)));
        var group=queries.groups(filter(),"agent").getList().getFirst();
        var cost=((List<Map<String,Object>>)group.get("costs")).getFirst();
        assertEquals(new BigDecimal("20.000000000"),cost.get("payableAmount"));
        assertEquals(new BigDecimal("3.000000000"),cost.get("paidAmount"));
        assertNull(cost.get("unpaidAmount"));
    }

    private Map<String,Object> deviceContext() {
        AgentEntity agent=new AgentEntity();agent.setId("agent-a");agent.setWorkspaceId("workspace-a");agent.setAgentName("Historic A");
        DeviceEntity device=new DeviceEntity();device.setId("device-a");device.setAgentId("agent-a");device.setWorkspaceId("workspace-a");device.setAlias("Historic device");
        Map<String,Object> runtime=new HashMap<>(Map.of("model_config",Map.of("model-a",Map.of())));contexts.attach(runtime,agent,device);
        return (Map<String,Object>)runtime.get("billing_context");
    }
    private UsageEventRequest event(Map<String,Object> context,String id) {
        UsageEventRequest event=new UsageEventRequest();event.setEventId(id);event.setContextId(context.get("context_id").toString());event.setContextToken(context.get("context_token").toString());
        event.setModelId("model-a");event.setServiceType("LLM");event.setPurpose("CONVERSATION");event.setStartedAt(Instant.now().minusSeconds(1));event.setEndedAt(Instant.now());event.setStatus("SUCCESS");event.setUsageComplete(true);
        event.setUsage(List.of(Usage.builder().metric("input_tokens").unit("token").source("PROVIDER").quantity(new BigDecimal("123")).build()));return event;
    }
    private Map<String,Object> accept(UsageEventRequest event){UsageBatchRequest batch=new UsageBatchRequest();batch.setEvents(List.of(event));return usage.accept(batch);}
    private String rejection(Map<String,Object> response){return ((List<Map<String,String>>)response.get("rejected")).getFirst().get("reasonCode");}
    private BillingFilter filter(){BillingFilter f=new BillingFilter();f.setStartDate("2026-10-01");f.setEndDate("2026-10-31");return f;}
    private BillingBindingRequest binding(){var r=new BillingBindingRequest();r.setProvider("VOLCENGINE");r.setAccountRef("account-a");r.setScopeType("PROJECT");r.setScopeValue("project-a");r.setWorkspaceId("workspace-a");r.setAgentId("agent-a");r.setValidFrom(Instant.parse("2026-10-01T00:00:00Z"));return r;}
    private Map<String,Object> officialRtcBill(String expenseEnd) {
        var source=mapper.createObjectNode().put("BillID","rtc-prepaid-open-end").put("BillPeriod","2026-10")
                .put("Product","RTC").put("Currency","CNY").put("PayableAmount","20.123456789").put("PaidAmount","12.5")
                .put("ExpenseBeginTime","2026-10-01 08:00:00").put("ExpenseEndTime",expenseEnd);
        return bills.official(source,"account-a","2026-10",false);
    }
    private Map<String,Object> bill(String level,String billId,String detailId,String amount){
        Map<String,Object> row=new TreeMap<>();row.put("provider","VOLCENGINE");row.put("account_ref","account-a");row.put("bill_id",billId);row.put("bill_detail_id",detailId);row.put("level",level);row.put("bill_period","2026-10");row.put("project","project-a");row.put("currency","CNY");row.put("payable_amount",amount);row.put("source","STATEMENT_IMPORT");row.put("expense_begin",LocalDateTime.parse("2026-10-01T00:00:00"));row.put("expense_end",LocalDateTime.parse("2026-10-01T01:00:00"));return bills.normalize(row);
    }
}

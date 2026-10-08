package xiaozhi.modules.hotword;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.sql.Timestamp;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.hutool.json.JSONObject;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.ModelConfigService;
import xiaozhi.modules.workspace.service.WorkspacePermissionService;

class HotwordServiceTest {
    JdbcTemplate db = mock(JdbcTemplate.class);
    WorkspacePermissionService permission = mock(WorkspacePermissionService.class);
    ModelConfigService models = mock(ModelConfigService.class);
    AgentDao agents = mock(AgentDao.class);
    VolcengineHotwordClient vendor = mock(VolcengineHotwordClient.class);
    PlatformTransactionManager transaction = mock(PlatformTransactionManager.class);
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    HotwordService service;
    Map<String,Object> row;
    @BeforeEach void setup() {
        when(transaction.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(permission.currentWorkspaceId()).thenReturn("workspace");
        service = new HotwordService(db,new ObjectMapper(),permission,models,agents,vendor,events,transaction);
        row = new HashMap<>(Map.of("id","local","workspace_id","workspace","name","词库","model_id","model",
                "entries_json","[{\"word\":\"慧苑\",\"weight\":4}]","status","PUBLISHED","remote_id","old",
                "update_date",new Timestamp(System.currentTimeMillis())));
        when(db.queryForList(startsWith("SELECT * FROM ai_asr_hotword WHERE"),eq("local"),eq("workspace")))
                .thenAnswer(call -> List.of(new HashMap<>(row)));
        when(db.queryForList(startsWith("SELECT remote_id"),eq(String.class),eq("local"))).thenReturn(List.of("old","older"));
        when(db.update(anyString(),any(Object[].class))).thenReturn(1);
        when(agents.selectList(any())).thenReturn(List.of());
        ModelConfigEntity model = new ModelConfigEntity(); model.setId("model"); model.setModelType("ASR"); model.setIsEnabled(1); model.setTestStatus(1);
        model.setConfigJson(new JSONObject().set("type","doubao_stream").set("api_key","test-fixture"));
        when(models.getModelByIdFromCache("model")).thenReturn(model);
    }
    @Test void normalizationProducesWeightedFileWithoutTrailingNewline() {
        var entries = HotwordService.normalize(List.of(new HotwordSaveDTO.Entry(" 慧苑 ",null),new HotwordSaveDTO.Entry("小智",10)));
        assertEquals("慧苑|4\n小智|10",HotwordService.contents(entries));
        assertThrows(RenException.class,()->HotwordService.normalize(List.of(new HotwordSaveDTO.Entry("慧苑|10",4))));
        assertThrows(RenException.class,()->HotwordService.normalize(List.of(new HotwordSaveDTO.Entry("a\nb",4))));
        assertThrows(RenException.class,()->HotwordService.normalize(List.of(new HotwordSaveDTO.Entry("a",4),new HotwordSaveDTO.Entry(" a ",4))));
        assertThrows(RenException.class,()->HotwordService.normalize(List.of(new HotwordSaveDTO.Entry("汉".repeat(11),4))));
    }
    @Test void publishVerificationFailureRetainsPreviouslyPublishedId() {
        when(vendor.find(anyString(),anyString())).thenReturn("new-version");
        doThrow(new RenException("核对未通过")).when(vendor).verify(anyString(),eq("new-version"),anyList());
        service.publish("local");
        verify(vendor,never()).update(anyString(),anyString(),anyString(),anyString());
        verify(db).update(startsWith("UPDATE ai_asr_hotword SET remote_id=?,status=?"),eq("old"),eq("FAILED"),eq("核对未通过"),eq("local"),anyString());
        verify(vendor,never()).create(anyString(),anyString(),anyString());
    }
    @Test void retriesRecoverExistingVersionInsteadOfRegisteringAgain() {
        when(vendor.find(anyString(),anyString())).thenReturn("recovered");
        service.publish("local");
        verify(vendor).verify(eq("test-fixture"),eq("recovered"),anyList());
        verify(vendor,never()).create(anyString(),anyString(),anyString());
        verify(db).update(startsWith("UPDATE ai_asr_hotword SET remote_id=?,status=?"),eq("recovered"),eq("PUBLISHED"),eq(""),eq("local"),anyString());
    }
    @Test void rejectsUnknownOrOtherWorkspaceBeforeVendorAccess() {
        when(db.queryForList(startsWith("SELECT * FROM ai_asr_hotword WHERE"),eq("local"),eq("workspace"))).thenReturn(List.of());
        assertThrows(RenException.class,()->service.publish("local"));
        verifyNoInteractions(vendor);
    }
    @Test void rejectsBindingToOtherWorkspaceAndModel() {
        AgentEntity agent = new AgentEntity(); agent.setId("agent"); agent.setWorkspaceId("other");
        when(agents.selectById("agent")).thenReturn(agent);
        assertThrows(RenException.class,()->service.apply("local",List.of("agent"),false));
        agent.setWorkspaceId("workspace"); agent.setAsrModelId("other-model");
        assertThrows(RenException.class,()->service.apply("local",List.of("agent"),false));
        verify(events,never()).publishEvent(any(Object.class));
    }
    @Test void unapplyRemovesAllHistoricalReferencesAndKeepsUnrelatedCandidates() {
        AgentEntity agent = new AgentEntity(); agent.setId("agent"); agent.setWorkspaceId("workspace");
        agent.setAsrHotwordId("older"); agent.setAsrHotwordIds(List.of("older","old","unrelated"));
        when(agents.selectById("agent")).thenReturn(agent);
        service.apply("local",List.of("agent"),true);
        verify(db).update(startsWith("UPDATE ai_agent SET"),isNull(),eq("[\"unrelated\"]"),eq("agent"),eq("workspace"));
    }
    @Test void publishingCannotBeEditedOrAppliedWhileRunning() {
        row.put("status","PUBLISHING");
        assertThrows(RenException.class,()->service.apply("local",List.of("agent"),false));
        assertThrows(RenException.class,()->service.update("local",new HotwordSaveDTO("更新","model",List.of(new HotwordSaveDTO.Entry("小智",4)))));
    }
    @Test void deletionRejectsHistoricalCandidateOrTemplateReferences() {
        when(db.queryForObject(startsWith("SELECT COUNT(*) FROM ai_agent WHERE"),eq(Long.class),anyString(),anyString())).thenReturn(1L);
        assertThrows(RenException.class,()->service.delete("local"));
        verifyNoInteractions(vendor);
    }
    @Test void changedCredentialsCannotMoveLibraryToAnotherVendorAccount() {
        row.put("credential_fingerprint","different-fingerprint");
        assertThrows(RenException.class,()->service.apply("local",List.of("agent"),false));
        verify(db,never()).update(startsWith("UPDATE ai_agent SET"),any(Object[].class));
        service.publish("local");
        verifyNoInteractions(vendor);
        verify(db).update(startsWith("UPDATE ai_asr_hotword SET remote_id=?,status=?"),eq("old"),eq("FAILED"),contains("凭据已变更"),eq("local"),anyString());
    }
    @Test void failedRemoteDeletionKeepsLocalRevisionForRetry() {
        when(db.queryForList("SELECT * FROM ai_asr_hotword_revision WHERE hotword_id=?","local"))
                .thenReturn(List.of(Map.of("remote_name","stable-name","remote_id","old")));
        when(vendor.find(anyString(),eq("stable-name"))).thenThrow(new RenException("暂时不可用"));
        assertThrows(RenException.class,()->service.delete("local"));
        verify(db,never()).update(startsWith("DELETE FROM ai_asr_hotword"),any(Object[].class));
        verify(db).update(startsWith("UPDATE ai_asr_hotword SET remote_id=?,status=?"),eq("old"),eq("FAILED"),eq("暂时不可用"),eq("local"),anyString());
    }
    @Test void readOnlyWorkspaceCannotCreatePublishOrApply() {
        doThrow(new RenException("只读工作空间")).when(permission).requireWrite("workspace");
        assertThrows(RenException.class,()->service.create(new HotwordSaveDTO("词库","model",List.of(new HotwordSaveDTO.Entry("慧苑",4)))));
        assertThrows(RenException.class,()->service.publish("local"));
        assertThrows(RenException.class,()->service.apply("local",List.of("agent"),false));
        verifyNoInteractions(vendor);
        verify(db,never()).update(anyString(),any(Object[].class));
    }
}

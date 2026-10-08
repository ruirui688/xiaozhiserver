package xiaozhi.modules.hotword;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.shiro.subject.Subject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.security.user.SecurityUser;

class HotwordImportServiceTest {
    final ObjectMapper mapper = new ObjectMapper();
    final HotwordService hotwords = mock(HotwordService.class);
    final HotwordCredentialSources sources = mock(HotwordCredentialSources.class);
    final VolcengineHotwordClient vendor = mock(VolcengineHotwordClient.class);
    final JdbcTemplate db = mock(JdbcTemplate.class);
    final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    final HotwordImportService service = new HotwordImportService(hotwords, sources, vendor, db, mapper, transactions);
    final Map<String,Object> owner = Map.of("id","local","workspace_id","workspace","credential_fingerprint","source","status","PUBLISHED");
    @AfterEach void close() { service.close(); }

    @Test void parsesOriginalFileIncludingWeightsBomAndWindowsLines() throws Exception {
        var table = mapper.readTree("{\"WordCount\":2,\"File\":\"\uFEFF慧苑|8\\r\\n小智\\n\",\"Preview\":[\"wrong\"]}");
        var entries = HotwordImportService.parseEntries(table);
        assertEquals(List.of(new HotwordSaveDTO.Entry("慧苑",8),new HotwordSaveDTO.Entry("小智",4)),entries);
    }
    @Test void supportsCompletePreviewAndRejectsTruncatedOrInvalidData() throws Exception {
        assertEquals(4,HotwordImportService.parseEntries(mapper.readTree("{\"WordCount\":1,\"Preview\":[\"慧苑\"]}")).get(0).weight());
        for (String json : List.of("{\"WordCount\":2,\"Preview\":[\"慧苑\"]}",
                "{\"WordCount\":1,\"Preview\":[{}]}","{\"WordCount\":1,\"File\":\"慧苑|11\"}",
                "{\"WordCount\":1,\"File\":\"慧苑|bad\"}","{\"WordCount\":2,\"File\":\"慧苑\\n慧苑\"}")) {
            var table = mapper.readTree(json);
            assertThrows(RenException.class,()->HotwordImportService.parseEntries(table));
        }
    }
    @Test void ordinaryUsersCannotEnumerateOrImportSharedCredentials() {
        try (var security = mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getSubject).thenReturn(mock(Subject.class));
            assertThrows(RenException.class,()->service.remote());
            assertThrows(RenException.class,()->service.importRemote(new HotwordImportDTO("source","remote")));
            verifyNoInteractions(hotwords,vendor,db);
        }
    }
    @Test void repeatedImportReturnsExistingWithoutRemoteMutation() {
        admin(() -> {
            when(db.queryForList(anyString(),eq("remote"))).thenReturn(List.of(owner));
            when(hotwords.view(owner)).thenReturn(Map.of("id","local"));
            assertEquals("local",service.importRemote(new HotwordImportDTO("source","remote")).get("id"));
            verifyNoInteractions(vendor);
            verify(db,never()).update(anyString(),any(Object[].class));
        });
    }
    @Test void anotherWorkspaceCannotClaimAnExistingImport() {
        admin(() -> {
            when(hotwords.workspace(true)).thenReturn("other");
            when(db.queryForList(anyString(),eq("remote"))).thenReturn(List.of(owner));
            assertThrows(RenException.class,()->service.importRemote(new HotwordImportDTO("source","remote")));
            verifyNoInteractions(vendor);
        });
    }
    @Test void readOnlyWorkspaceCannotImportBeforeVendorAccess() {
        admin(() -> {
            when(hotwords.workspace(true)).thenThrow(new RenException("只读"));
            assertThrows(RenException.class,()->service.importRemote(new HotwordImportDTO("source","remote")));
            verifyNoInteractions(vendor);
        });
    }
    @Test void listHidesTablesClaimedByOtherWorkspacesOrModels() {
        admin(() -> {
            when(vendor.list("fixture")).thenReturn(List.of(
                    mapper.createObjectNode().put("BoostingTableID","mine"),
                    mapper.createObjectNode().put("BoostingTableID","other-workspace"),
                    mapper.createObjectNode().put("BoostingTableID","other-model"),
                    mapper.createObjectNode().put("BoostingTableID","unclaimed")));
            when(db.queryForList(anyString(),eq("mine"))).thenReturn(List.of(owner));
            when(db.queryForList(anyString(),eq("other-workspace"))).thenReturn(List.of(Map.of("workspace_id","other","credential_fingerprint","source")));
            when(db.queryForList(anyString(),eq("other-model"))).thenReturn(List.of(Map.of("workspace_id","workspace","credential_fingerprint","other")));
            var result = (List<Map<String,Object>>) service.remote().get("tables");
            assertEquals(List.of("mine","unclaimed"),result.stream().map(row -> row.get("remoteId")).toList());
            assertEquals(true,result.get(0).get("imported"));
            assertEquals(false,result.get(1).get("imported"));
        });
    }
    @Test void incompatibleLegacyReferencesBlockClaimWithoutChangingAgents() {
        admin(() -> {
            when(db.queryForList(startsWith("SELECT workspace_id,asr_model_id FROM ai_agent WHERE"),anyString(),anyString())).thenReturn(List.of(Map.of("workspace_id","other","asr_model_id","model")));
            assertThrows(RenException.class,()->service.importRemote(new HotwordImportDTO("source","remote")));
            verify(db,never()).update(anyString(),any(Object[].class));
        });
    }
    @Test void oneBrokenSourceDoesNotHideOtherSourceResultsOrLeakRawErrors() {
        admin(() -> {
            var good = new HotwordCredentialSources.Source("source","火山来源 1","fixture");
            var bad = new HotwordCredentialSources.Source("bad","火山来源 2","bad-fixture");
            when(sources.discover()).thenReturn(new HotwordCredentialSources.Discovery(List.of(bad,good),false));
            when(vendor.list("bad-fixture")).thenThrow(new RenException("Authorization: secret-value"));
            when(vendor.list("fixture")).thenReturn(List.of(mapper.createObjectNode().put("BoostingTableID","remote")));
            var result = service.remote();
            assertEquals(1,((List<?>)result.get("tables")).size());
            assertEquals(1,((List<?>)result.get("errors")).size());
            assertFalse(result.toString().contains("secret-value"));
        });
    }
    @Test void sharedRemoteIdUsesItsPinnedSourceInsteadOfAnotherKey() {
        admin(() -> {
            var other = new HotwordCredentialSources.Source("other","火山来源 1","other-fixture");
            var owned = new HotwordCredentialSources.Source("source","火山来源 2","fixture");
            when(sources.discover()).thenReturn(new HotwordCredentialSources.Discovery(List.of(other,owned),false));
            when(vendor.list(anyString())).thenReturn(List.of(mapper.createObjectNode().put("BoostingTableID","remote")));
            when(db.queryForList(anyString(),eq("remote"))).thenReturn(List.of(owner));
            var tables = (List<Map<String,Object>>)service.remote().get("tables");
            assertEquals(1,tables.size());
            assertEquals("source",tables.get(0).get("sourceId"));
        });
    }
    @Test void compatibleReferencesAcrossDifferentModelsPermitImport() {
        admin(() -> {
            when(db.queryForList(startsWith("SELECT workspace_id,asr_model_id FROM ai_agent WHERE"),anyString(),anyString()))
                    .thenReturn(List.of(Map.of("workspace_id","workspace","asr_model_id","recording")));
            when(sources.forModel("recording",false)).thenReturn("source");
            when(db.queryForList(startsWith("SELECT h.*"),eq("remote"))).thenReturn(List.of(),List.of(owner));
            when(hotwords.view(owner)).thenReturn(Map.of("id","local"));
            assertEquals("local",service.importRemote(new HotwordImportDTO("source","remote")).get("id"));
        });
    }
    @Test void importPreservesRemoteIdAndNeverMutatesVendor() {
        admin(() -> {
            when(db.queryForList(anyString(),eq("remote"))).thenReturn(List.of(),List.of(owner));
            when(hotwords.view(owner)).thenReturn(Map.of("id","local"));
            service.importRemote(new HotwordImportDTO("source","remote"));
            verify(db).update(startsWith("INSERT INTO ai_asr_hotword("),anyString(),eq("workspace"),eq("原词库"),contains("慧苑"),eq("remote"),eq("source"));
            verify(db).update(startsWith("INSERT INTO ai_asr_hotword_revision"),startsWith("import_"),anyString(),eq("remote"));
            verify(vendor).get("fixture","remote");
            verifyNoMoreInteractions(vendor);
        });
    }
    @Test void concurrentImportReturnsCommittedWinnerAfterUniqueConflict() {
        admin(() -> {
            when(db.queryForList(anyString(),eq("remote"))).thenReturn(List.of(),List.of(owner));
            when(db.update(startsWith("INSERT INTO ai_asr_hotword_revision"),any(),any(),any())).thenThrow(new DuplicateKeyException("fixture"));
            when(hotwords.view(owner)).thenReturn(Map.of("id","local"));
            assertEquals("local",service.importRemote(new HotwordImportDTO("source","remote")).get("id"));
            verify(transactions).rollback(any());
        });
    }
    private void admin(Runnable check) {
        try (var security = mockStatic(SecurityUser.class)) {
            Subject subject = mock(Subject.class);
            when(subject.isPermitted("sys:role:superAdmin")).thenReturn(true);
            security.when(SecurityUser::getSubject).thenReturn(subject);
            when(hotwords.workspace(anyBoolean())).thenReturn("workspace");
            var source = new HotwordCredentialSources.Source("source","火山引擎","fixture");
            when(sources.require("source")).thenReturn(source);
            when(sources.discover()).thenReturn(new HotwordCredentialSources.Discovery(List.of(source),false));
            when(hotwords.sourceId(anyMap())).thenAnswer(call -> ((Map<?,?>)call.getArgument(0)).get("credential_fingerprint"));
            when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
            when(vendor.get("fixture","remote")).thenReturn(mapper.createObjectNode().put("BoostingTableName","原词库").put("WordCount",1).put("File","慧苑|8"));
            check.run();
        }
    }
}

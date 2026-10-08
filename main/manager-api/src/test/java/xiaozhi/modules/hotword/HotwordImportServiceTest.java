package xiaozhi.modules.hotword;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.shiro.subject.Subject;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.security.user.SecurityUser;

class HotwordImportServiceTest {
    final ObjectMapper mapper = new ObjectMapper();
    final HotwordService hotwords = mock(HotwordService.class);
    final VolcengineHotwordClient vendor = mock(VolcengineHotwordClient.class);
    final JdbcTemplate db = mock(JdbcTemplate.class);
    final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    final HotwordImportService service = new HotwordImportService(hotwords, vendor, db, mapper, transactions);
    final Map<String,Object> owner = Map.of("id","local","workspace_id","workspace","model_id","model","status","PUBLISHED");

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
            assertThrows(RenException.class,()->service.remote("model"));
            assertThrows(RenException.class,()->service.importRemote(new HotwordImportDTO("model","remote")));
            verifyNoInteractions(hotwords,vendor,db);
        }
    }
    @Test void repeatedImportReturnsExistingWithoutRemoteMutation() {
        admin(() -> {
            when(db.queryForList(anyString(),eq("remote"))).thenReturn(List.of(owner));
            when(hotwords.view(owner)).thenReturn(Map.of("id","local"));
            assertEquals("local",service.importRemote(new HotwordImportDTO("model","remote")).get("id"));
            verifyNoInteractions(vendor);
            verify(db,never()).update(anyString(),any(Object[].class));
        });
    }
    @Test void anotherWorkspaceCannotClaimAnExistingImport() {
        admin(() -> {
            when(hotwords.workspace(true)).thenReturn("other");
            when(db.queryForList(anyString(),eq("remote"))).thenReturn(List.of(owner));
            assertThrows(RenException.class,()->service.importRemote(new HotwordImportDTO("model","remote")));
            verifyNoInteractions(vendor);
        });
    }
    @Test void readOnlyWorkspaceCannotImportBeforeVendorAccess() {
        admin(() -> {
            when(hotwords.workspace(true)).thenThrow(new RenException("只读"));
            assertThrows(RenException.class,()->service.importRemote(new HotwordImportDTO("model","remote")));
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
            when(db.queryForList(anyString(),eq("other-workspace"))).thenReturn(List.of(Map.of("workspace_id","other","model_id","model")));
            when(db.queryForList(anyString(),eq("other-model"))).thenReturn(List.of(Map.of("workspace_id","workspace","model_id","other")));
            var result = service.remote("model");
            assertEquals(List.of("mine","unclaimed"),result.stream().map(row -> row.get("remoteId")).toList());
            assertEquals(true,result.get(0).get("imported"));
            assertEquals(false,result.get(1).get("imported"));
        });
    }
    @Test void incompatibleLegacyReferencesBlockClaimWithoutChangingAgents() {
        admin(() -> {
            when(db.queryForObject(startsWith("SELECT COUNT(*) FROM ai_agent WHERE"),eq(Long.class),any(),any(),any(),any())).thenReturn(1L);
            assertThrows(RenException.class,()->service.importRemote(new HotwordImportDTO("model","remote")));
            verify(db,never()).update(anyString(),any(Object[].class));
        });
    }
    @Test void importPreservesRemoteIdAndNeverMutatesVendor() {
        admin(() -> {
            when(db.queryForList(anyString(),eq("remote"))).thenReturn(List.of(),List.of(owner));
            when(hotwords.view(owner)).thenReturn(Map.of("id","local"));
            service.importRemote(new HotwordImportDTO("model","remote"));
            verify(db).update(startsWith("INSERT INTO ai_asr_hotword("),anyString(),eq("workspace"),eq("原词库"),eq("model"),contains("慧苑"),eq("remote"),eq(HotwordService.digest("fixture")));
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
            assertEquals("local",service.importRemote(new HotwordImportDTO("model","remote")).get("id"));
            verify(transactions).rollback(any());
        });
    }
    private void admin(Runnable check) {
        try (var security = mockStatic(SecurityUser.class)) {
            Subject subject = mock(Subject.class);
            when(subject.isPermitted("sys:role:superAdmin")).thenReturn(true);
            security.when(SecurityUser::getSubject).thenReturn(subject);
            when(hotwords.workspace(anyBoolean())).thenReturn("workspace");
            when(hotwords.key("model")).thenReturn("fixture");
            when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
            when(vendor.get("fixture","remote")).thenReturn(mapper.createObjectNode().put("BoostingTableName","原词库").put("WordCount",1).put("File","慧苑|8"));
            check.run();
        }
    }
}

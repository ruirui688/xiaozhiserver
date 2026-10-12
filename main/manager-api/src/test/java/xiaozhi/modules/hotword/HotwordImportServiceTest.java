package xiaozhi.modules.hotword;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.user.UserDetail;
import xiaozhi.modules.security.user.SecurityUser;

class HotwordImportServiceTest {
    final ObjectMapper mapper=new ObjectMapper();
    final HotwordImportService service=new HotwordImportService();
    @Test void parsesOriginalFileIncludingWeightsBomAndWindowsLines() throws Exception {
        var table=mapper.readTree("{\"WordCount\":2,\"File\":\"\uFEFF慧苑|8\\r\\n小智\\n\",\"Preview\":[\"wrong\"]}");
        assertEquals(List.of(new HotwordSaveDTO.Entry("慧苑",8),new HotwordSaveDTO.Entry("小智",4)),HotwordImportService.parseEntries(table));
    }
    @Test void supportsCompletePreviewAndRejectsTruncatedOrInvalidData() throws Exception {
        assertEquals(4,HotwordImportService.parseEntries(mapper.readTree("{\"WordCount\":1,\"Preview\":[\"慧苑\"]}")).getFirst().weight());
        for(String json:List.of("{\"WordCount\":2,\"Preview\":[\"慧苑\"]}","{\"WordCount\":1,\"Preview\":[{}]}","{\"WordCount\":1,\"File\":\"慧苑|11\"}","{\"WordCount\":1,\"File\":\"慧苑|bad\"}","{\"WordCount\":2,\"File\":\"慧苑\\n慧苑\"}")) {
            var table=mapper.readTree(json);assertThrows(RenException.class,()->HotwordImportService.parseEntries(table));
        }
    }
    @Test void neitherTenantNorEmployeeCanEnumerateOrImportSharedSupplierTables() {
        for(String audience:List.of("USER","OPS")) {
            try(var security=mockStatic(SecurityUser.class)) {
                UserDetail principal=new UserDetail();principal.setAudience(audience);principal.setId("USER".equals(audience)?1L:null);principal.setEmployeeId("OPS".equals(audience)?"employee":null);
                security.when(SecurityUser::getUser).thenReturn(principal);
                assertFalse(HotwordImportService.canImport());
                assertTrue(assertThrows(RenException.class,service::remote).getMsg().contains("尚未接入独立凭据"));
                assertTrue(assertThrows(RenException.class,()->service.importRemote(new HotwordImportDTO("old-shared-source","remote"))).getMsg().contains("尚未接入独立凭据"));
            }
        }
    }
    @Test void blockedRemoteImportDoesNotCallWorkspaceMutationAndLocalEditingStillWorks() {
        HotwordService hotwords=mock(HotwordService.class);HotwordController controller=new HotwordController(hotwords,service);
        assertThrows(RenException.class,controller::remote);
        assertThrows(RenException.class,()->controller.importRemote(new HotwordImportDTO("source","remote")));
        verifyNoInteractions(hotwords);
        HotwordSaveDTO input=new HotwordSaveDTO("Local vocabulary","a".repeat(64),List.of(new HotwordSaveDTO.Entry("小智",4)));
        when(hotwords.create(input)).thenReturn(Map.of("id","local","status","DRAFT"));
        assertEquals("local",controller.create(input).getData().get("id"));verify(hotwords).create(input);
        when(hotwords.list()).thenReturn(List.of(Map.of("id","local")));
        assertEquals(1,controller.list().getData().size());verify(hotwords).list();
    }
}

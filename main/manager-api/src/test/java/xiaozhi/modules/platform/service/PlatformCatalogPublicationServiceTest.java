package xiaozhi.modules.platform.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Date;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.junit.jupiter.api.Test;
import xiaozhi.modules.model.dao.ModelConfigDao;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.security.ops.OpsAccess;
import xiaozhi.modules.security.ops.OpsApprovalService;

class PlatformCatalogPublicationServiceTest {
    @Test void publicationHashSurvivesHttpSerializationAndApprovalJsonRoundTrip() throws Exception {
        var models=mock(ModelConfigDao.class);var runtime=mock(PlatformRuntimeService.class);
        var approvals=new OpsApprovalService(null,new ObjectMapper(),null,null);
        var model=new ModelConfigEntity();model.setId("model");model.setModelType("LLM");
        model.setTestStatus(1);model.setTestTime(new Date(1791806400123L));
        when(models.selectById("model")).thenReturn(model);
        when(runtime.approved("model","LLM",true)).thenReturn(new PlatformRuntimeService.Runtime(model,"DEEPSEEK",
                Map.of("max_tokens",4096L,"nested",List.of(Map.of("longValue",9007199254740993L)))));
        var service=new PlatformCatalogPublicationService(null,null,models,null,runtime,mock(PlatformRateService.class),approvals,null,null);
        try(var access=mockStatic(OpsAccess.class)) {
            var preview=service.preview("model");
            var http=new ObjectMapper().registerModule(new SimpleModule().addSerializer(Long.class,ToStringSerializer.instance)
                    .addSerializer(Long.TYPE,ToStringSerializer.instance));
            var received=new ObjectMapper().readTree(http.writeValueAsString(preview));
            assertTrue(received.path("payload").path("testTime").isTextual());
            assertEquals(preview.get("version"),approvals.hash(received.get("payload")));
            assertEquals(approvals.hash(service.preview("model").get("payload")),approvals.hash(received.get("payload")));
            model.setTestTime(new Date(1791806400124L));
            assertNotEquals(preview.get("version"),service.preview("model").get("version"));
        }
    }
}

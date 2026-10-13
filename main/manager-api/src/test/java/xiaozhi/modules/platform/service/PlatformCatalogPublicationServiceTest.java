package xiaozhi.modules.platform.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
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
                Map.of("max_tokens",500.0,"top_p",1.0,"temperature",new BigDecimal("0.7000"),
                        "nested",List.of(Map.of("longValue",9007199254740993L,"small",1.0E-7,"large",1.0E20)))));
        var service=new PlatformCatalogPublicationService(null,null,models,null,runtime,mock(PlatformRateService.class),approvals,null,null);
        try(var access=mockStatic(OpsAccess.class)) {
            var preview=service.preview("model");
            var http=new ObjectMapper().registerModule(new SimpleModule().addSerializer(Long.class,ToStringSerializer.instance)
                    .addSerializer(Long.TYPE,ToStringSerializer.instance));
            var received=new ObjectMapper().readTree(http.writeValueAsString(preview));
            assertTrue(received.path("payload").path("testTime").isTextual());
            var parameters=received.path("payload").path("runtime");
            assertEquals("500",parameters.path("max_tokens").textValue());
            assertEquals("1",parameters.path("top_p").textValue());
            assertEquals("0.7",parameters.path("temperature").textValue());
            assertEquals("9007199254740993",parameters.path("nested").get(0).path("longValue").textValue());
            assertEquals("0.0000001",parameters.path("nested").get(0).path("small").textValue());
            assertEquals("100000000000000000000",parameters.path("nested").get(0).path("large").textValue());
            assertEquals(preview.get("version"),approvals.hash(received.get("payload")));
            assertEquals(approvals.hash(service.preview("model").get("payload")),approvals.hash(received.get("payload")));
            model.setTestTime(new Date(1791806400124L));
            assertNotEquals(preview.get("version"),service.preview("model").get("version"));
        }
    }
    @Test void rejectsNonFinitePublicationNumbers() {
        var models=mock(ModelConfigDao.class);var runtime=mock(PlatformRuntimeService.class);
        var model=new ModelConfigEntity();model.setModelType("LLM");model.setTestStatus(1);
        when(models.selectById("model")).thenReturn(model);
        var service=new PlatformCatalogPublicationService(null,null,models,null,runtime,mock(PlatformRateService.class),
                new OpsApprovalService(null,new ObjectMapper(),null,null),null,null);
        try(var access=mockStatic(OpsAccess.class)) {
            for(double invalid:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}) {
                when(runtime.approved("model","LLM",true)).thenReturn(new PlatformRuntimeService.Runtime(model,"DEEPSEEK",Map.of("temperature",invalid)));
                assertThrows(xiaozhi.common.exception.RenException.class,()->service.preview("model"));
            }
        }
    }
}

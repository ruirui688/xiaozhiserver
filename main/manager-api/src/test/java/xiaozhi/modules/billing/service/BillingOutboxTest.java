package xiaozhi.modules.billing.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import xiaozhi.modules.billing.service.BillingUsageRecorder.InternalUsage;

class BillingOutboxTest {
    @TempDir Path directory;
    @Test void durableRetrySurvivesRestartAndDatabaseFailureWithoutDroppingEvent() throws Exception {
        var mapper=new ObjectMapper().findAndRegisterModules();var service=mock(BillingUsageService.class);
        ObjectProvider<BillingUsageService> provider=mock(ObjectProvider.class);when(provider.getObject()).thenReturn(service);
        Path root=directory.resolve("outbox");var first=new BillingInternalOutbox(mapper,provider,root.toString());
        first.store(event("event-outbox-1"));
        Path file=root.resolve("event-outbox-1.json");assertTrue(Files.exists(file));
        assertEquals(PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(file));
        doThrow(new DataAccessResourceFailureException("offline")).when(service).persistInternal(any());
        var restarted=new BillingInternalOutbox(mapper,provider,root.toString());restarted.retry();assertTrue(Files.exists(file));
        doNothing().when(service).persistInternal(any());restarted.retry();assertFalse(Files.exists(file));
        assertEquals("READY",restarted.status().get("status"));verify(service,times(2)).persistInternal(any());
    }
    @Test void corruptRecordIsQuarantinedWithoutBlockingOtherEvents() throws Exception {
        var mapper=new ObjectMapper().findAndRegisterModules();var service=mock(BillingUsageService.class);
        ObjectProvider<BillingUsageService> provider=mock(ObjectProvider.class);when(provider.getObject()).thenReturn(service);
        Path root=directory.resolve("outbox");var outbox=new BillingInternalOutbox(mapper,provider,root.toString());
        outbox.store(event("event-valid-01"));Files.writeString(root.resolve("event-corrupt.json"),"invalid-json");
        outbox.retry();verify(service).persistInternal(any());
        assertEquals(0L,outbox.status().get("pendingCount"));assertEquals(1L,outbox.status().get("rejectedCount"));assertEquals("INCOMPLETE",outbox.status().get("status"));
    }
    private InternalUsage event(String id){return InternalUsage.builder().eventId(id).modelId("model-a").provider("DEEPSEEK").serviceType("LLM").purpose("TITLE").startedAt(Instant.now()).endedAt(Instant.now()).status("SUCCESS").usageComplete(false).usage(List.of()).build();}
}

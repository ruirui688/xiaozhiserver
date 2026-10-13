package xiaozhi.modules.platform.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.billing.dto.BillingFilter;
import xiaozhi.modules.platform.service.PlatformUserUsageService;
import xiaozhi.modules.security.ops.OpsAccess;

class PlatformUserUsageAuthorizationTest {
    @Test void everyUsageEndpointRejectsNonUserIdentityBeforeQueryingTenantData() {
        var service=mock(PlatformUserUsageService.class);var controller=new PlatformUserUsageController(service);
        List<Consumer<PlatformUserUsageController>> calls=List.of(c->c.options(),c->c.overview(new BillingFilter()),c->c.groups(new BillingFilter(),"agent"),c->c.events(new BillingFilter()));
        try(var access=mockStatic(OpsAccess.class)) {
            access.when(OpsAccess::requireUser).thenThrow(new RenException("USER_IDENTITY_REQUIRED"));
            for(var call:calls)assertThrows(RenException.class,()->call.accept(controller));
            verifyNoInteractions(service);
        }
    }
}

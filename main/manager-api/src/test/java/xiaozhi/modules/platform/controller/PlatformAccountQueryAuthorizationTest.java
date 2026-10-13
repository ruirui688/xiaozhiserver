package xiaozhi.modules.platform.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.user.UserDetail;
import xiaozhi.common.utils.MessageUtils;
import xiaozhi.modules.platform.service.PlatformAccountQueryService;
import xiaozhi.modules.security.ops.OpsGrant;
import xiaozhi.modules.security.user.SecurityUser;

class PlatformAccountQueryAuthorizationTest {
    @Test
    void requiresExistingPlatformAllowancePermissionBeforeQueryingUsernames() {
        var queries = mock(PlatformAccountQueryService.class);
        var controller = new PlatformOpsController(null, queries, null, null, null, null, null,
                null, null, null, null, null, null);
        try (var security = mockStatic(SecurityUser.class); var messages = mockStatic(MessageUtils.class)) {
            messages.when(() -> MessageUtils.getMessage(ErrorCode.NO_PERMISSION)).thenReturn("no permission");
            UserDetail employee = new UserDetail();
            employee.setAudience("OPS");
            employee.setEmployeeId("employee");
            security.when(SecurityUser::getUser).thenReturn(employee);
            for (var grants : List.of(
                    List.of(new OpsGrant("ALLOWANCE_POOL", "PLATFORM", "platform-services", Set.of("pool.read"))),
                    List.of(new OpsGrant("ALLOWANCE", "USER", "42", Set.of("allowance.read"))),
                    List.of(new OpsGrant("ALLOWANCE", "PLATFORM", "other-domain", Set.of("allowance.read"))))) {
                employee.setGrants(grants);
                assertEquals(ErrorCode.NO_PERMISSION, assertThrows(RenException.class,
                        () -> controller.accounts(1, 20, "alice")).getCode());
            }
            employee.setGrants(List.of(new OpsGrant("ALLOWANCE", "PLATFORM", "platform-services", Set.of("allowance.read"))));
            employee.setAudience("USER");
            employee.setId(42L);
            assertEquals(ErrorCode.NO_PERMISSION, assertThrows(RenException.class,
                    () -> controller.accounts(1, 20, "alice")).getCode());
            verifyNoInteractions(queries);

            employee.setAudience("OPS");
            employee.setId(null);
            var page = Map.<String, Object>of("list", List.of(), "total", 0L, "page", 2, "limit", 10);
            when(queries.accounts(2, 10, "alice")).thenReturn(page);
            assertSame(page, controller.accounts(2, 10, "alice").getData());
            verify(queries).accounts(2, 10, "alice");
        }
    }
}

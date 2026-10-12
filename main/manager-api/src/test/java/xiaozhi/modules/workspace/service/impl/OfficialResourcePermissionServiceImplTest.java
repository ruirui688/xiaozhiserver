package xiaozhi.modules.workspace.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mockStatic;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import xiaozhi.common.user.UserDetail;
import xiaozhi.modules.security.ops.OpsGrant;
import xiaozhi.modules.security.ops.OpsPermissionCatalog;
import xiaozhi.modules.security.user.SecurityUser;

class OfficialResourcePermissionServiceImplTest {
    @Test void tenantMembershipNeverGrantsOfficialMaintenance() {
        var service=new OfficialResourcePermissionServiceImpl();UserDetail user=new UserDetail();user.setId(7L);user.setAudience("USER");
        try(MockedStatic<SecurityUser> security=mockStatic(SecurityUser.class)){
            security.when(SecurityUser::getUser).thenReturn(user);assertFalse(service.isOfficialMaintainer());
        }
    }
    @Test void onlyScopedEmployeeContentPermissionGrantsMaintenance() {
        var service=new OfficialResourcePermissionServiceImpl();UserDetail user=new UserDetail();user.setEmployeeId("content-editor");user.setAudience("OPS");
        var template=OpsPermissionCatalog.TEMPLATES.get("OFFICIAL_CONTENT");
        user.setGrants(List.of(new OpsGrant(template.code(),"PLATFORM",template.domain(),template.permissions())));
        try(MockedStatic<SecurityUser> security=mockStatic(SecurityUser.class)){
            security.when(SecurityUser::getUser).thenReturn(user);assertTrue(service.isOfficialMaintainer());
            user.setGrants(List.of(new OpsGrant(template.code(),"PLATFORM","another-domain",template.permissions())));
            assertFalse(service.isOfficialMaintainer());
        }
    }
}

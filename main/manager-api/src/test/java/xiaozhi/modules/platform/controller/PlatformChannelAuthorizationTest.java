package xiaozhi.modules.platform.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import xiaozhi.common.exception.*;
import xiaozhi.common.user.UserDetail;
import xiaozhi.common.utils.MessageUtils;
import xiaozhi.modules.platform.service.*;
import xiaozhi.modules.security.ops.OpsGrant;
import xiaozhi.modules.security.user.SecurityUser;

class PlatformChannelAuthorizationTest {
    @Test void userAndOtherOpsRolesCannotReadOrChangeChannels() {
        var channels=mock(PlatformChannelService.class);var mutations=mock(PlatformOpsMutationService.class);
        var controller=new PlatformChannelController(channels,mutations);
        var input=new PlatformChannelService.Input("channel-auth-test",null,null,"Model","model","account",null,null,true,20,Map.of("api_key","fake-secret"),null);
        try(var security=mockStatic(SecurityUser.class);var messages=mockStatic(MessageUtils.class)) {
            messages.when(()->MessageUtils.getMessage(ErrorCode.NO_PERMISSION)).thenReturn("no permission");
            var employee=new UserDetail();employee.setEmployeeId("employee");employee.setAudience("OPS");security.when(SecurityUser::getUser).thenReturn(employee);
            for(var grant:List.of(new OpsGrant("FINANCE","PLATFORM","platform-services",Set.of("pool.read","allowance.review")),new OpsGrant("RESOURCE","PLATFORM","platform-services",Set.of("catalog.model.read")),new OpsGrant("KEYS","USER","1",Set.of("credentials.read","credentials.write")))) {
                employee.setGrants(List.of(grant));assertThrows(RenException.class,()->controller.list(1,20,null,null,null));assertThrows(RenException.class,controller::models);assertThrows(RenException.class,()->controller.save(input));
            }
            employee.setGrants(List.of(new OpsGrant("KEYS","PLATFORM","platform-services",Set.of("credentials.read","credentials.write"))));employee.setAudience("USER");employee.setId(1L);
            assertThrows(RenException.class,controller::models);assertThrows(RenException.class,()->controller.save(input));verifyNoInteractions(channels,mutations);
            employee.setAudience("OPS");employee.setId(null);
            for(String permission:List.of("rates.read","pool.adjust")) {
                employee.setGrants(List.of(new OpsGrant("EXISTING-ROLE","PLATFORM","platform-services",Set.of(permission))));
                controller.models();assertThrows(RenException.class,()->controller.list(1,20,null,null,null));assertThrows(RenException.class,()->controller.save(input));
            }
            employee.setGrants(List.of(new OpsGrant("KEYS","PLATFORM","platform-services",Set.of("credentials.read","credentials.write"))));
            controller.models();controller.save(input);
            verify(channels,times(3)).models();verify(mutations).perform(eq("channels.write"),eq("model"),any());
        }
    }
    @Test void malformedCredentialBodyUsesConstantError(){assertEquals("INVALID_CHANNEL_INPUT",new PlatformChannelController(null,null).malformed().getMsg());}
}

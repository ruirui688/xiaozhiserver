package xiaozhi.modules.platform.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import xiaozhi.common.exception.RenException;

class PlatformCatalogSecretsTest {
    @Test void nestedAndAliasedCredentialsCannotBecomePublicModelParameters() {
        Map<String,Object> input=Map.of("max_tokens",1024,"headers",Map.of("Authorization","Bearer fake-secret","X-Api-Key","fake-secret"),"nested",List.of(Map.of("Api-Key","fake-secret")));
        assertThrows(RenException.class,()->PlatformCatalogSecrets.validate(input));
        Object publicConfig=PlatformCatalogSecrets.publicValue(input);assertFalse(publicConfig.toString().contains("fake-secret"));assertTrue(publicConfig.toString().contains("max_tokens=1024"));
    }
    @Test void runtimeEndpointRejectsPrivateHostsCredentialsQueriesAndSpoofedHosts() {
        for(String endpoint:List.of("http://api.deepseek.com/v1","https://localhost/v1","https://127.0.0.1/v1","https://api.deepseek.com.attacker.invalid/v1","https://key@api.deepseek.com/v1","https://api.deepseek.com/v1?api_key=oops","https://api.deepseek.com:444/v1"))assertThrows(RenException.class,()->PlatformRuntimeService.validateEndpoint(endpoint,"LLM"));
        assertEquals("DEEPSEEK",PlatformRuntimeService.validateEndpoint("https://api.deepseek.com/v1","LLM"));
    }
}

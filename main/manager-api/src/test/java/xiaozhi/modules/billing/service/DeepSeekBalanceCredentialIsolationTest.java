package xiaozhi.modules.billing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import xiaozhi.common.exception.RenException;
import static org.junit.jupiter.api.Assertions.*;

class DeepSeekBalanceCredentialIsolationTest {
    @Test void absentDedicatedCredentialFailsBeforeAnySupplierCall() {
        var client = new DeepSeekBalanceClient(new ObjectMapper(), "", "finance-account");
        assertFalse(client.enabled());
        assertThrows(RenException.class, client::fetch);
    }

    @Test void accountIdentityMustBeExplicitRatherThanInferredFromCredential() {
        var client = new DeepSeekBalanceClient(new ObjectMapper(), "test-only-nonfunctional-key", "");
        assertFalse(client.enabled());
        assertThrows(RenException.class, client::fetch);
    }
}

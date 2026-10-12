package xiaozhi.modules.platform.service;

import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import xiaozhi.modules.security.ops.OpsAccess;
import xiaozhi.modules.security.ops.OpsIdentityService;

/** Credential/catalog writes and their non-secret audit entry commit together. */
@Service @RequiredArgsConstructor
public class PlatformOpsMutationService {
    private final TransactionTemplate tx;
    private final OpsIdentityService identities;
    public <T> T perform(String action,String target,Supplier<T> operation) {
        String actor=OpsAccess.employee().getEmployeeId();
        return tx.execute(status->{T result=operation.get();identities.audit(actor,action,target,"SUCCESS");return result;});
    }
}

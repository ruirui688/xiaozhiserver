package xiaozhi.modules.platform.service;

import jakarta.validation.constraints.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.security.CredentialCipher;
import xiaozhi.modules.platform.dto.PlatformRequests.Settle;
import static xiaozhi.modules.platform.service.PlatformSupport.*;

/** Manual supplier-evidence reconciliation. Never silently converts an unknown call to zero. */
@Service @RequiredArgsConstructor
public class PlatformResolutionService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CredentialCipher cipher;
    private final PlatformAdmissionService admission;
    public record Resolution(@NotBlank String requestId,@NotBlank @Size(max=500) String evidenceRef,@NotBlank @Size(max=200) String providerRequestId,@NotEmpty Map<String,String> usage) {}
    public Map<String,Object> resolve(String id,Resolution request,String operator) {
        return tx.execute(status->{
            String requestId=requestId(request.requestId());String fingerprint=hash(id+"|"+request.evidenceRef()+"|"+request.providerRequestId()+"|"+new TreeMap<>(request.usage()));
            List<Map<String,Object>> previous=jdbc.queryForList("SELECT payload_hash FROM ai_platform_resolution WHERE request_id=?",requestId);
            if(!previous.isEmpty()){if(!fingerprint.equals(previous.getFirst().get("payload_hash")))throw new RenException("IDEMPOTENCY_CONFLICT");return map("resolved",true);}
            Map<String,Object> row=jdbc.queryForMap("SELECT state,permit_ciphertext FROM ai_platform_reservation WHERE id=?",id);
            if(!"UNKNOWN".equals(row.get("state")))throw new RenException("RESERVATION_NOT_UNKNOWN");
            Map<String,Object> result=admission.settle(new Settle(id,cipher.decrypt("platform-permit:"+id,row.get("permit_ciphertext").toString()),"SUCCESS",true,request.usage(),request.providerRequestId()));
            if(!"SETTLED".equals(result.get("state")))throw new RenException("SUPPLIER_USAGE_REQUIRES_REVIEW");
            jdbc.update("INSERT INTO ai_platform_resolution(request_id,reservation_id,evidence_ref,operator_id,payload_hash,created_at) VALUES(?,?,?,?,?,?)",requestId,id,required(request.evidenceRef(),500),operator,fingerprint,now());
            return result;
        });
    }
}

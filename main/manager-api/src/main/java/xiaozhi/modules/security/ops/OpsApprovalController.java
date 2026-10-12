package xiaozhi.modules.security.ops;
import java.util.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import xiaozhi.common.utils.Result;
@RestController @RequestMapping("/ops/approvals") @RequiredArgsConstructor
public class OpsApprovalController {
    private final OpsApprovalService approvals;
    @PostMapping public Result<Map<String,Object>> propose(@Valid @RequestBody OpsApprovalService.Proposal request){return new Result<Map<String,Object>>().ok(approvals.propose(request));}
    @GetMapping public Result<List<Map<String,Object>>> list(){return new Result<List<Map<String,Object>>>().ok(approvals.list());}
    @PostMapping("/{id}/approve") public Result<Void> approve(@PathVariable String id){approvals.approve(id);return new Result<>();}
}

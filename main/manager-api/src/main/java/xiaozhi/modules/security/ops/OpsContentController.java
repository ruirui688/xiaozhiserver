package xiaozhi.modules.security.ops;
import java.util.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import xiaozhi.common.utils.Result;
@RestController @RequestMapping("/ops/content") @RequiredArgsConstructor
public class OpsContentController {
    private final OpsTemplateService templates;
    private final OpsKnowledgeService knowledge;
    @GetMapping("/knowledge") public Result<List<Map<String,Object>>> knowledge(){return new Result<List<Map<String,Object>>>().ok(knowledge.list());}
    @GetMapping("/knowledge/{id}") public Result<Map<String,Object>> knowledge(@PathVariable String id){return new Result<Map<String,Object>>().ok(knowledge.get(id));}
    @PostMapping("/knowledge") public Result<Map<String,Object>> createKnowledge(@Valid @RequestBody OpsKnowledgeService.Draft input){return new Result<Map<String,Object>>().ok(knowledge.create(input));}
    @PutMapping("/knowledge/{id}") public Result<Map<String,Object>> updateKnowledge(@PathVariable String id,@Valid @RequestBody OpsKnowledgeService.Draft input){return new Result<Map<String,Object>>().ok(knowledge.update(id,input));}
    @PostMapping("/knowledge/{id}/clone") public Result<Map<String,Object>> cloneKnowledge(@PathVariable String id){return new Result<Map<String,Object>>().ok(knowledge.cloneDraft(id));}
    @GetMapping("/knowledge/{id}/prepare") public Result<Map<String,Object>> prepareKnowledge(@PathVariable String id){return new Result<Map<String,Object>>().ok(knowledge.prepare(id));}
    @PostMapping("/knowledge/{id}/publish") public Result<Void> publishKnowledge(@PathVariable String id){knowledge.publish(id);return new Result<>();}
    @GetMapping("/templates") public Result<List<Map<String,Object>>> templates(){return new Result<List<Map<String,Object>>>().ok(templates.list());}
    @GetMapping("/templates/{id}") public Result<Map<String,Object>> template(@PathVariable String id){return new Result<Map<String,Object>>().ok(templates.get(id));}
    @PostMapping("/templates") public Result<Map<String,Object>> create(@Valid @RequestBody Map<String,Object> input){return new Result<Map<String,Object>>().ok(templates.create(input));}
    @PutMapping("/templates/{id}") public Result<Map<String,Object>> update(@PathVariable String id,@Valid @RequestBody Map<String,Object> input){return new Result<Map<String,Object>>().ok(templates.update(id,input));}
    @PostMapping("/templates/{id}/clone") public Result<Map<String,Object>> cloneDraft(@PathVariable String id){return new Result<Map<String,Object>>().ok(templates.cloneDraft(id));}
    @GetMapping("/templates/{id}/prepare") public Result<Map<String,Object>> prepare(@PathVariable String id){return new Result<Map<String,Object>>().ok(templates.prepare(id));}
    @PostMapping("/templates/{id}/publish") public Result<Void> publish(@PathVariable String id){templates.publish(id);return new Result<>();}
}

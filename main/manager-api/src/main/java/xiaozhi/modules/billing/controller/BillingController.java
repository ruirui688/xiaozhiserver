package xiaozhi.modules.billing.controller;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import xiaozhi.common.page.PageData;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.billing.dto.*;
import xiaozhi.modules.billing.service.*;

@RestController @RequestMapping("/billing") @RequiredArgsConstructor
public class BillingController {
    private final BillingQueryService queries;
    private final BillingBindingService bindings;
    private final BillingCsvService csv;
    private final BillingSyncService sync;

    @GetMapping("/overview") public Result<Object> overview(BillingFilter filter) { return ok(queries.overview(filter)); }
    @GetMapping("/groups") public Result<Object> groups(BillingFilter filter,@RequestParam String groupBy) { return ok(queries.groups(filter,groupBy)); }
    @GetMapping("/events") public Result<Object> events(BillingFilter filter) { return ok(queries.events(filter)); }
    @RequiresPermissions("supplier.bill.read")
    @GetMapping("/bills") public Result<Object> bills(BillingFilter filter) { return ok(queries.bills(filter)); }
    @xiaozhi.modules.security.ops.SharedOfficialEndpoint
    @GetMapping("/options") public Result<Object> options() { return ok(queries.options()); }

    @RequiresPermissions("supplier.bill.import")
    @GetMapping("/bindings") public Result<Object> bindings(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int limit) { return ok(bindings.page(page,limit)); }
    @RequiresPermissions("supplier.bill.import")
    @PostMapping("/bindings") public Result<Object> binding(@Valid @RequestBody BillingBindingRequest request) { return ok(bindings.save(request)); }
    @RequiresPermissions("supplier.bill.import")
    @DeleteMapping("/bindings/{id}") public Result<Object> delete(@PathVariable String id) { bindings.delete(id);return ok(Map.of("deleted",true)); }
    @RequiresPermissions("supplier.bill.sync")
    @PostMapping("/sync") public Result<Object> sync(@Valid @RequestBody SyncRequest request) { return ok(sync.sync(request.getBillPeriod(),request.getProvider(),request.getAccountRef())); }
    @RequiresPermissions("supplier.bill.import")
    @GetMapping("/import-template") public ResponseEntity<byte[]> template() {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=official-bill-template.csv")
                .contentType(new MediaType("text","csv",StandardCharsets.UTF_8)).body(csv.template().getBytes(StandardCharsets.UTF_8));
    }
    @RequiresPermissions("supplier.bill.import")
    @PostMapping(value="/import",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<Object> upload(@RequestParam MultipartFile file,@RequestParam String provider,@RequestParam String accountRef) {
        int count=csv.importFile(file,provider,accountRef);
        return ok(Map.of("status","SUCCESS","importedCount",count));
    }

    @Data public static class SyncRequest {
        @NotBlank @Pattern(regexp="\\d{4}-\\d{2}") private String billPeriod;
        @Pattern(regexp="VOLCENGINE|DEEPSEEK") private String provider;
        @Size(max=128) private String accountRef;
    }

    private Result<Object> ok(Object value) { return new Result<>().ok(present(value)); }
    /** Exact decimal strings and explicit UTC instants prevent browser precision/time-zone loss. */
    static Object present(Object value) {
        if(value instanceof BigDecimal amount) return amount.toPlainString();
        if(value instanceof java.sql.Timestamp time) return time.toLocalDateTime().toInstant(ZoneOffset.UTC).toString();
        if(value instanceof LocalDateTime time) return time.toInstant(ZoneOffset.UTC).toString();
        if(value instanceof PageData<?> page) return Map.of("list",present(page.getList()),"total",page.getTotal());
        if(value instanceof Map<?,?> map) {
            Map<String,Object> result=new LinkedHashMap<>();map.forEach((key,item)->result.put(key.toString(),present(item)));return result;
        }
        if(value instanceof Collection<?> list) return list.stream().map(BillingController::present).toList();
        return value;
    }
}

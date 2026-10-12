package xiaozhi.modules.billing.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import xiaozhi.common.exception.RenException;

@Service @RequiredArgsConstructor
public class BillingBillService {
    private final JdbcTemplate jdbc;
    private final BillingSupport support;
    private final BillingBindingService bindings;
    private static final Map<String, String> FIELDS = Map.ofEntries(
            Map.entry("bill_id","BillID"),Map.entry("bill_detail_id","BillDetailId"),Map.entry("bill_period","BillPeriod"),
            Map.entry("product","Product"),Map.entry("project","Project"),Map.entry("resource_id","InstanceNo"),
            Map.entry("currency","Currency"),Map.entry("settlement_currency","CurrencySettlement"),Map.entry("payable_amount","PayableAmount"),Map.entry("paid_amount","PaidAmount"),
            Map.entry("unpaid_amount","UnpaidAmount"),Map.entry("coupon_amount","CouponAmount"),Map.entry("settlement_type","SettlementType"),
            Map.entry("pay_status","PayStatus"),Map.entry("bill_category","BillCategory"));
    private static final List<String> AMOUNTS=List.of("payable_amount","paid_amount","unpaid_amount","coupon_amount");

    public Map<String, Object> official(JsonNode source, String account, String period, boolean detail) {
        Map<String, Object> row=new TreeMap<>();
        FIELDS.forEach((column, field) -> row.put(column, scalar(source.get(field))));
        row.put("provider","VOLCENGINE"); row.put("account_ref",account); row.put("level",detail ? "DETAIL" : "BILL");
        if (row.get("bill_period")==null) row.put("bill_period",period);
        row.put("expense_begin", parseTime(scalar(source.get("ExpenseBeginTime"))));
        row.put("expense_end", parseTime(scalar(source.get("ExpenseEndTime"))));
        row.put("source","VOLCENGINE_API"); row.put("service_type",classify(Objects.toString(row.get("product"),"")));
        if (row.get("bill_category")==null) row.put("bill_category",scalar(source.get("BillCategoryParent")));
        if(row.get("resource_id")==null)row.put("resource_id",scalar(source.get("ResourceID")));
        Map<String,Object> supplierUsage=new TreeMap<>();
        for(String field:List.of("BillingMode","SettlementType","BillDetailNum","CurrencySettlement","Count","Unit","UseDuration","UseDurationUnit","DeductionCount","DeductionUseDuration","CouponAmount","PointDeductAmount","SavingPlanDeductionDiscountAmount","ResourceID")) {
            String value=scalar(source.get(field));if(value!=null && value.length()<=512)supplierUsage.put(field,value);
        }
        row.put("supplier_usage",supplierUsage);
        return normalize(row);
    }

    Map<String, Object> normalize(Map<String, Object> row) {
        if (!Set.of("VOLCENGINE", "DEEPSEEK").contains(row.get("provider"))) throw new RenException("不支持此供应商账单");
        required(row,"account_ref",128); required(row,"bill_id",256); required(row,"bill_period",7); required(row,"currency",12);
        if (!Set.of("BILL","DETAIL").contains(row.get("level"))) throw new RenException("账单层级必须为 BILL 或 DETAIL");
        if ("DETAIL".equals(row.get("level"))) required(row,"bill_detail_id",256);
        if (!row.get("currency").toString().matches("[A-Z]{3,12}")) throw new RenException("账单币种无效");
        if(row.get("settlement_currency")!=null && !row.get("settlement_currency").toString().matches("[A-Z]{3,12}"))throw new RenException("结算币种无效");
        try { YearMonth.parse(row.get("bill_period").toString()); }
        catch (Exception e) { throw new RenException("账期格式无效"); }
        if (!(row.get("expense_begin") instanceof LocalDateTime begin) || !(row.get("expense_end") instanceof LocalDateTime end) || begin.isAfter(end)) throw new RenException("账单消费期间无效");
        for (String column : AMOUNTS) row.put(column, amount(row.get(column)));
        if (AMOUNTS.stream().allMatch(key -> row.get(key)==null)) throw new RenException("账单缺少官方金额，不能由用量推算");
        for (String field: List.of("product","project","resource_id","settlement_type","pay_status","bill_category","model_id","source_reference","service_type")) {
            Object value=row.get(field); int limit=Set.of("project","resource_id","source_reference").contains(field) ? 255 : "product".equals(field) ? 128 : 64;
            if (value!=null && value.toString().length()>limit) throw new RenException("账单字段过长");
        }
        row.put("service_type",BillingSupport.service(Objects.toString(row.get("service_type"),"OTHER")));
        Map<String, Object> evidence=new TreeMap<>(row);
        row.put("evidence_json", support.json(evidence)); row.put("evidence_hash",BillingSupport.hash(support.json(evidence)));
        // JSON tuple prevents delimiter collisions in supplier identifiers.
        row.put("id",BillingSupport.hash(support.json(List.of(row.get("provider"),row.get("account_ref"),row.get("level"),row.get("bill_id"),Objects.toString(row.get("bill_detail_id"),"")))));
        row.remove("source_reference");
        row.remove("supplier_usage");
        return row;
    }

    @Transactional
    public int save(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return 0;
        String provider=rows.getFirst().get("provider").toString(), account=rows.getFirst().get("account_ref").toString();
        bindings.lockAccount(provider,account);
        Set<String> ids=new HashSet<>();
        for (Map<String, Object> row : rows) {
            if (!provider.equals(row.get("provider")) || !account.equals(row.get("account_ref")) || !ids.add(row.get("id").toString())) throw new RenException("账单批次包含重复或不一致标识");
            if("STATEMENT_IMPORT".equals(row.get("source")) && jdbc.queryForObject("SELECT COUNT(*) FROM ai_billing_bill WHERE provider=? AND account_ref=? AND bill_id=? AND source='VOLCENGINE_API'",Long.class,provider,account,row.get("bill_id"))>0)
                throw new RenException("此账单已有官方API快照，不能用人工导入覆盖，请重新同步官方账单");
            row.put("workspace_id",null); row.put("agent_id",null); row.put("device_id",null); row.put("binding_id",null);
            row.putAll(bindings.attribution(row)); row.put("synced_at",BillingSupport.time(Instant.now()));
            // Replacement and its evidence update are atomic; official IDs remain stable across corrections.
            jdbc.update("DELETE FROM ai_billing_bill WHERE id=?",row.get("id"));
            BillingUsageService.insert(jdbc,"ai_billing_bill",row);
        }
        return rows.size();
    }

    @Transactional
    public int replacePeriod(String provider,String account,String period,List<Map<String,Object>> rows) {
        bindings.lockAccount(provider,account);
        for(var row:rows) if(!provider.equals(row.get("provider")) || !account.equals(row.get("account_ref")) || !period.equals(row.get("bill_period"))) throw new RenException("供应商返回了不匹配的账期");
        Set<Object> detailed=new HashSet<>();
        for(var row:rows) if("DETAIL".equals(row.get("level"))) detailed.add(row.get("bill_id"));
        for(var row:rows) {
            boolean complete="BILL".equals(row.get("level")) && detailed.contains(row.get("bill_id"));
            var evidence=support.object(row.get("evidence_json").toString());
            if(complete && evidence.get("supplier_usage") instanceof Map<?,?> metrics && metrics.get("BillDetailNum")!=null) {
                try { long expected=Long.parseLong(metrics.get("BillDetailNum").toString());
                    long actual=rows.stream().filter(item->"DETAIL".equals(item.get("level")) && Objects.equals(row.get("bill_id"),item.get("bill_id"))).count();
                    complete=actual==expected;
                }catch(NumberFormatException e){complete=false;}
            }
            row.put("detail_complete",complete ? 1 : 0);
        }
        jdbc.update("DELETE FROM ai_billing_bill WHERE provider=? AND account_ref=? AND bill_period=?",provider,account,period);
        return save(rows);
    }

    static LocalDateTime parseTime(String text) {
        if (text==null || text.isBlank()) return null;
        try { return BillingSupport.time(Instant.parse(text)); }
        catch (Exception ignored) {
            try { return BillingSupport.time(LocalDateTime.parse(text,DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atZone(ZoneId.of("Asia/Shanghai")).toInstant()); }
            catch (Exception e) { throw new RenException("消费时间必须包含时分秒或 ISO-8601 时区"); }
        }
    }
    static BigDecimal amount(Object value) {
        if (value==null || value.toString().isBlank()) return null;
        try {
            BigDecimal amount=new BigDecimal(value.toString());
            if (amount.scale()>9 || amount.precision()-amount.scale()>21) throw new IllegalArgumentException();
            return amount;
        } catch (Exception e) { throw new RenException("账单金额无效或超出可保存精度"); }
    }
    private void required(Map<String,Object> row,String key,int max) {
        Object value=row.get(key); if(value==null || value.toString().isBlank() || value.toString().length()>max) throw new RenException("账单缺少有效的 "+key);
    }
    private static String scalar(JsonNode value) { return value==null || value.isNull() || !value.isValueNode() || value.asText().isBlank() ? null : value.asText(); }
    static String classify(String product) {
        String name=product.toLowerCase(Locale.ROOT);
        if(name.contains("tts") || name.contains("speech_synthesis")) return "TTS";
        if(name.contains("asr") || name.contains("speech_recognition")) return "ASR";
        if(name.equals("ark") || name.contains("deepseek")) return "LLM";
        return "OTHER";
    }
}

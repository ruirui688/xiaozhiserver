package xiaozhi.modules.billing.service;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.page.PageData;
import xiaozhi.modules.billing.dto.BillingAccountFilter;

/** Supplier-account statements are independent of workspace attribution and consumption-date filters. */
@Service @RequiredArgsConstructor
public class BillingAccountService {
    private final JdbcTemplate jdbc;
    private final BillingSupport support;
    private final VolcengineBillingClient volcengine;
    private final DeepSeekBalanceClient deepseek;

    public Map<String,Object> accounts() {
        BillingQueryService.requireAdmin();
        Map<String,Map<String,Object>> accounts=new LinkedHashMap<>();
        addAccount(accounts,"VOLCENGINE",volcengine.accountRef());
        addAccount(accounts,"DEEPSEEK",deepseek.accountRef());
        for(var row:jdbc.queryForList("SELECT DISTINCT provider,account_ref FROM ai_billing_bill ORDER BY provider,account_ref"))
            addAccount(accounts,row.get("provider").toString(),row.get("account_ref").toString());
        return Map.of("list",new ArrayList<>(accounts.values()));
    }
    private void addAccount(Map<String,Map<String,Object>> values,String provider,String account) {
        if(account==null || account.isBlank())return;
        String label="VOLCENGINE".equals(provider)?"火山引擎":"DEEPSEEK".equals(provider)?"DeepSeek":provider;
        var capabilities=capabilities(provider,account);
        values.putIfAbsent(provider+":"+account,Map.of("provider",provider,"accountRef",account,"label",label+" · "+account,
                "billingEnabled",capabilities.get("billSyncEnabled"),"capabilities",capabilities));
    }
    private Map<String,Object> capabilities(String provider,String account) {
        boolean billSync="VOLCENGINE".equals(provider),balance="DEEPSEEK".equals(provider);
        boolean billEnabled=billSync && account.equals(volcengine.accountRef()) && volcengine.enabled();
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("billSyncSupported",billSync);result.put("billSyncEnabled",billEnabled);
        result.put("balanceSupported",balance);result.put("balanceEnabled",balance && account.equals(deepseek.accountRef()) && deepseek.enabled());
        result.put("statementImportSupported",billSync || balance);result.put("statementImportFormat","PROJECT_TEMPLATE");
        result.put("nativeStatementAdapter",false);
        result.put("reasonCode",balance?"NO_VERIFIED_PUBLIC_BILLING_API":billEnabled?null:"BILL_SYNC_NOT_CONFIGURED");
        return result;
    }
    public void requireAccount(String provider,String account) {
        BillingQueryService.requireAdmin();
        if(provider==null || account==null || account.isBlank())throw new RenException("请选择供应商账号");
        boolean configured="VOLCENGINE".equals(provider) && account.equals(volcengine.accountRef())
                || "DEEPSEEK".equals(provider) && account.equals(deepseek.accountRef());
        if(!configured && jdbc.queryForObject("SELECT COUNT(*) FROM ai_billing_bill WHERE provider=? AND account_ref=?",Long.class,provider,account)==0)
            throw new RenException("供应商账号不存在");
    }
    private void authorize(BillingAccountFilter f) { BillingQueryService.requireAdmin();f.validate();requireAccount(f.getProvider(),f.getAccountRef()); }

    public Map<String,Object> status(BillingAccountFilter f) { authorize(f);return syncStatus(f); }
    private Map<String,Object> syncStatus(BillingAccountFilter f) {
        var runs=jdbc.queryForList("SELECT status,started_at,finished_at,error_code FROM ai_billing_sync_run WHERE provider=? AND account_ref=? AND bill_period=? ORDER BY started_at DESC,id DESC LIMIT 1",f.getProvider(),f.getAccountRef(),f.getBillPeriod());
        Object success=jdbc.queryForObject("SELECT MAX(finished_at) FROM ai_billing_sync_run WHERE provider=? AND account_ref=? AND bill_period=? AND status='SUCCESS'",Object.class,f.getProvider(),f.getAccountRef(),f.getBillPeriod());
        Object snapshot=jdbc.queryForObject("SELECT MAX(synced_at) FROM ai_billing_bill WHERE provider=? AND account_ref=? AND bill_period=?",Object.class,f.getProvider(),f.getAccountRef(),f.getBillPeriod());
        Object imported=jdbc.queryForObject("SELECT MAX(synced_at) FROM ai_billing_bill WHERE provider=? AND account_ref=? AND bill_period=? AND source='STATEMENT_IMPORT'",Object.class,f.getProvider(),f.getAccountRef(),f.getBillPeriod());
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("scope","ACCOUNT");result.put("provider",f.getProvider());result.put("accountRef",f.getAccountRef());result.put("billPeriod",f.getBillPeriod());
        var capabilities=capabilities(f.getProvider(),f.getAccountRef());result.put("capabilities",capabilities);
        boolean syncSupported=Boolean.TRUE.equals(capabilities.get("billSyncSupported"));
        String state=runs.isEmpty() ? snapshot==null ? "NOT_SYNCED" : "LEGACY_SNAPSHOT" : runs.getFirst().get("status").toString();
        if(imported!=null && (runs.isEmpty() || !syncSupported))state="IMPORTED_STATEMENT";
        else if(!syncSupported && snapshot==null)state="STATEMENT_REQUIRED";
        result.put("lastAttemptStatus",state);result.put("lastAttemptAt",runs.isEmpty()?null:runs.getFirst().get("started_at"));
        result.put("lastSuccessAt",syncSupported?success:null);result.put("lastImportedAt",imported);
        result.put("lastSnapshotAt",snapshot);result.put("snapshotAvailable",snapshot!=null || syncSupported && success!=null);
        Object observed=snapshot==null?success:snapshot;
        boolean old=observed!=null && BillingSupport.databaseTime(observed).isBefore(BillingSupport.time(Instant.now().minus(Duration.ofHours(24))));
        result.put("isStale",old || "FAILED".equals(state) || "RUNNING".equals(state));
        result.put("completenessVerified",syncSupported && success!=null && imported==null);return result;
    }

    public Map<String,Object> overview(BillingAccountFilter f) {
        authorize(f);List<Map<String,Object>> rows=load(f);
        var parents=level(rows,"BILL").stream().filter(r->f.getProduct()==null || f.getProduct().isBlank() || f.getProduct().equals(r.get("product"))).toList();
        Set<Object> selectedParents=new HashSet<>();parents.forEach(r->selectedParents.add(r.get("bill_id")));
        var details=level(rows,"DETAIL").stream().filter(r->f.getProduct()==null || f.getProduct().isBlank() || selectedParents.contains(r.get("bill_id")) || f.getProduct().equals(r.get("product"))).toList();
        Map<String,Object> sync=syncStatus(f), result=new LinkedHashMap<>();
        Map<String,Map<String,Object>> checks=reconcile(parents,details);
        long matched=checks.values().stream().filter(r->"MATCHED".equals(r.get("status"))).count();
        Set<Object> parentIds=new HashSet<>();parents.forEach(r->parentIds.add(r.get("bill_id")));
        long orphans=details.stream().filter(r->!parentIds.contains(r.get("bill_id"))).count();
        boolean available=(boolean)sync.get("snapshotAvailable");
        long unverified=parents.stream().filter(r->!paymentVerified(r)).count();
        boolean partial=orphans>0 || matched!=parents.size() || unverified>0 || !(boolean)sync.get("completenessVerified") || (boolean)sync.get("isStale");
        String coverage=!available ? "NOT_OBTAINED" : partial ? "PARTIAL" : rows.isEmpty() ? "EMPTY_CONFIRMED" : "SNAPSHOT";
        result.put("scope","ACCOUNT");result.put("provider",f.getProvider());result.put("accountRef",f.getAccountRef());result.put("billPeriod",f.getBillPeriod());
        result.put("timeBasis","BILL_PERIOD");result.put("amountBasis","OFFICIAL_PARENT_BILLS");result.put("costs",costs(parents));
        result.put("billCount",parents.size());result.put("detailCount",details.size());result.put("sync",sync);result.put("capabilities",sync.get("capabilities"));
        result.put("coverage",Map.of("status",coverage,"orphanDetailCount",orphans,"paymentCurrencyUnverifiedCount",unverified));
        result.put("reconciliation",Map.of("status",rows.isEmpty()?("EMPTY_CONFIRMED".equals(coverage)?"EMPTY_CONFIRMED":"NOT_OBTAINED"):matched==parents.size() && orphans==0 ? "MATCHED":"REVIEW_REQUIRED","matchedCount",matched,"mismatchCount",parents.size()-matched,"orphanDetailCount",orphans));
        Map<String,List<Map<String,Object>>> products=new TreeMap<>();
        for(var row:parents)products.computeIfAbsent(Objects.toString(row.get("product"),"UNKNOWN"),key->new ArrayList<>()).add(row);
        List<Map<String,Object>> groups=new ArrayList<>();
        products.forEach((product,bills)->groups.add(Map.of("product",product,"serviceType",BillingBillService.classify(product),"billCount",bills.size(),"costs",costs(bills))));
        result.put("products",groups);result.put("supportedFilters",List.of("provider","accountRef","billPeriod","product"));
        result.put("paymentBasis","所选账期账单截至快照的官方已付金额，不等于该月支付流水或净现金流");
        return result;
    }

    public PageData<Map<String,Object>> bills(BillingAccountFilter f) {
        authorize(f);
        String where=" WHERE provider=? AND account_ref=? AND bill_period=? AND level=?";
        List<Object> args=new ArrayList<>(List.of(f.getProvider(),f.getAccountRef(),f.getBillPeriod(),f.getLevel()));
        if(f.getBillId()!=null && !f.getBillId().isBlank()){where+=" AND bill_id=?";args.add(f.getBillId());}
        if(f.getProduct()!=null && !f.getProduct().isBlank()){where+=" AND product=?";args.add(f.getProduct());}
        long count=jdbc.queryForObject("SELECT COUNT(*) FROM ai_billing_bill"+where,Long.class,args.toArray());
        args.add(f.getLimit());args.add((long)(f.getPage()-1)*f.getLimit());
        var selected=jdbc.queryForList("SELECT * FROM ai_billing_bill"+where+" ORDER BY expense_begin DESC,bill_id,id LIMIT ? OFFSET ?",args.toArray());
        Set<String> billIds=new LinkedHashSet<>();selected.forEach(r->billIds.add(r.get("bill_id").toString()));
        var related=billIds.isEmpty()?List.<Map<String,Object>>of():loadMetadata(f,billIds);
        Map<String,Map<String,Object>> checks=reconcile(level(related,"BILL"),level(related,"DETAIL"));
        List<Map<String,Object>> page=new ArrayList<>();
        for(var row:selected) {
            Map<String,Object> value=new LinkedHashMap<>();
            row.forEach((key,item)->{if(!"evidence_json".equals(key))value.put(camel(key),item);});
            Map<String,Object> evidence=support.object(row.get("evidence_json").toString());value.put("evidence",evidence);
            value.put("supplierUsage",evidence.getOrDefault("supplier_usage",Map.of()));
            value.put("paymentCurrencyVerified",paymentVerified(row));
            if(!paymentVerified(row)){value.put("paidAmount",null);value.put("unpaidAmount",null);}
            value.put("aggregationIncluded","BILL".equals(row.get("level")));
            value.put("reconciliation",checks.getOrDefault(row.get("bill_id").toString(),Map.of("status","ORPHAN_DETAIL")));
            value.put("deductionStatus","UNVERIFIED");page.add(value);
        }
        return new PageData<>(page,count);
    }
    private List<Map<String,Object>> load(BillingAccountFilter f) {
        return loadMetadata(f,null);
    }
    private List<Map<String,Object>> loadMetadata(BillingAccountFilter f,Set<String> billIds) {
        // The supplier synchronizer itself caps each of its two source layers at 100,000 rows.
        String where=" WHERE provider=? AND account_ref=? AND bill_period=?";
        List<Object> args=new ArrayList<>(List.of(f.getProvider(),f.getAccountRef(),f.getBillPeriod()));
        if(billIds!=null){where+=" AND bill_id IN ("+String.join(",",Collections.nCopies(billIds.size(),"?"))+")";args.addAll(billIds);}
        var rows=jdbc.queryForList("SELECT bill_id,level,product,currency,settlement_currency,payable_amount,paid_amount,unpaid_amount,synced_at,CASE WHEN level='BILL' THEN evidence_json ELSE '{}' END evidence_json FROM ai_billing_bill"+where+" ORDER BY bill_id,level LIMIT 200001",args.toArray());
        if(rows.size()>200000)throw new RenException("账期记录超过安全查询上限，请联系管理员核对来源");
        return rows;
    }
    private List<Map<String,Object>> level(List<Map<String,Object>> rows,String level) { return rows.stream().filter(r->level.equals(r.get("level"))).toList(); }
    private boolean paymentVerified(Map<String,Object> r) { return r.get("settlement_currency")==null || Objects.equals(r.get("settlement_currency"),r.get("currency")); }
    private List<Map<String,Object>> costs(List<Map<String,Object>> rows) {
        Map<String,List<Map<String,Object>>> currencies=new TreeMap<>();
        rows.forEach(r->currencies.computeIfAbsent(r.get("currency").toString(),key->new ArrayList<>()).add(r));
        List<Map<String,Object>> result=new ArrayList<>();
        currencies.forEach((currency,items)->{
            Map<String,Object> value=new LinkedHashMap<>();value.put("currency",currency);value.put("payableAmount",sum(items,"payable_amount"));
            boolean verified=items.stream().allMatch(this::paymentVerified);value.put("paymentCurrencyVerified",verified);
            value.put("paidAmount",verified?sum(items,"paid_amount"):null);value.put("unpaidAmount",verified?sum(items,"unpaid_amount"):null);result.add(value);
        });return result;
    }
    private BigDecimal sum(List<Map<String,Object>> rows,String key) {
        BigDecimal result=BigDecimal.ZERO;
        for(var row:rows){if(row.get(key)==null)return null;result=result.add(new BigDecimal(row.get(key).toString()));}return result;
    }
    private Map<String,Map<String,Object>> reconcile(List<Map<String,Object>> parents,List<Map<String,Object>> details) {
        Map<String,List<Map<String,Object>>> groups=new HashMap<>();
        details.forEach(r->groups.computeIfAbsent(r.get("bill_id").toString(),key->new ArrayList<>()).add(r));
        Map<String,Map<String,Object>> results=new LinkedHashMap<>();
        for(var parent:parents) {
            var children=groups.getOrDefault(parent.get("bill_id").toString(),List.of());
            Map<String,Object> result=new LinkedHashMap<>();result.put("detailCount",children.size());
            var evidence=support.object(parent.get("evidence_json").toString());Long expected=null;
            if(evidence.get("supplier_usage") instanceof Map<?,?> usage && usage.get("BillDetailNum")!=null)
                try{expected=Long.valueOf(usage.get("BillDetailNum").toString());}catch(NumberFormatException ignored){ }
            result.put("expectedDetailCount",expected);
            boolean currency=children.stream().allMatch(r->Objects.equals(parent.get("currency"),r.get("currency")));
            BigDecimal amount=children.isEmpty() || !currency?null:sum(children,"payable_amount");
            BigDecimal parentAmount=parent.get("payable_amount")==null?null:new BigDecimal(parent.get("payable_amount").toString());
            BigDecimal delta=amount==null || parentAmount==null?null:amount.subtract(parentAmount);
            boolean count=expected!=null && expected==children.size();
            String status=children.isEmpty()?"NO_DETAILS":!currency?"CURRENCY_MISMATCH":!count?"COUNT_UNVERIFIED":delta==null?"AMOUNT_UNAVAILABLE":delta.signum()!=0?"AMOUNT_MISMATCH":"MATCHED";
            result.put("status",status);result.put("currencyVerified",currency);result.put("detailPayableAmount",amount);result.put("difference",delta);result.put("observedAt",parent.get("synced_at"));
            results.put(parent.get("bill_id").toString(),result);
        }return results;
    }
    private String camel(String name) { StringBuilder result=new StringBuilder();boolean upper=false;for(char c:name.toCharArray()){if(c=='_')upper=true;else{result.append(upper?Character.toUpperCase(c):c);upper=false;}}return result.toString(); }
}

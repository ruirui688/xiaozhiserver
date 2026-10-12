package xiaozhi.modules.billing.service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import xiaozhi.common.exception.RenException;

/** Admin-normalized official statements, not a claimed native supplier export format. */
@Service @RequiredArgsConstructor
public class BillingCsvService {
    private final BillingBillService bills;
    static final List<String> HEADERS=List.of("bill_id","bill_detail_id","level","bill_period","expense_begin","expense_end","product","service_type","model_id","project","resource_id","currency","payable_amount","paid_amount","unpaid_amount","coupon_amount","settlement_type","pay_status","bill_category","source_reference");
    public String template() { BillingQueryService.requireAdmin(); return String.join(",",HEADERS)+"\r\n"; }

    public int importFile(MultipartFile file,String provider,String account) {
        BillingQueryService.requireAdmin();
        if(file==null || file.isEmpty() || file.getSize()>5*1024*1024) throw new RenException("账单 CSV 不能为空且不能超过5MB");
        String filename=file.getOriginalFilename(),mime=file.getContentType();
        if(filename==null || !filename.toLowerCase(Locale.ROOT).endsWith(".csv") || !Set.of("text/csv","application/csv","application/vnd.ms-excel","text/plain").contains(Objects.toString(mime,""))) throw new RenException("请上传CSV文件（正确的扩展名和MIME类型）");
        try {
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(file.getBytes())).toString();
            return bills.save(parse(text,provider,account));
        } catch(IOException | IllegalArgumentException e) { throw new RenException("账单必须是有效 UTF-8 CSV"); }
    }

    List<Map<String,Object>> parse(String text,String provider,String account) {
        List<List<String>> records=records(text.startsWith("\uFEFF") ? text.substring(1) : text);
        if(records.isEmpty() || !records.getFirst().equals(HEADERS)) throw new RenException("CSV列与模板不一致；请按模板整理官方账单，不能用余额或用量代替金额");
        List<Map<String,Object>> rows=new ArrayList<>();
        for(int index=1;index<records.size();index++) {
            List<String> values=records.get(index);
            if(values.size()==1 && values.getFirst().isBlank()) continue;
            if(values.size()!=HEADERS.size()) throw new RenException("CSV第"+(index+1)+"行列数无效");
            Map<String,Object> row=new TreeMap<>();
            for(int col=0;col<HEADERS.size();col++) row.put(HEADERS.get(col),values.get(col).isBlank() ? null : values.get(col).trim());
            if(row.get("source_reference")==null) throw new RenException("每行必须填写官方账单凭证引用 source_reference");
            row.put("provider",provider);row.put("account_ref",account);row.put("source","STATEMENT_IMPORT");
            row.put("expense_begin",BillingBillService.parseTime((String)row.get("expense_begin")));
            row.put("expense_end",BillingBillService.parseTime((String)row.get("expense_end")));
            rows.add(bills.normalize(row));
        }
        if(rows.isEmpty()) throw new RenException("CSV没有账单数据");
        return rows;
    }

    static List<List<String>> records(String text) {
        List<List<String>> rows=new ArrayList<>();List<String> row=new ArrayList<>();StringBuilder field=new StringBuilder();
        boolean quoted=false,closed=false;
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(c=='\0') throw new RenException("CSV包含无效字符");
            if(quoted) {
                if(c=='"') { if(i+1<text.length() && text.charAt(i+1)=='"'){field.append('"');i++;}else{quoted=false;closed=true;} }
                else field.append(c);
            } else if(c=='"') {
                if(field.length()!=0 || closed) throw new RenException("CSV引号格式无效"); quoted=true;
            } else if(c==',' || c=='\n' || c=='\r') {
                row.add(field.toString());field.setLength(0);closed=false;
                if(c!=','){rows.add(row);row=new ArrayList<>();if(c=='\r' && i+1<text.length() && text.charAt(i+1)=='\n')i++;}
            } else { if(closed) throw new RenException("CSV引号后有无效内容");field.append(c); }
            if(field.length()>1024 || row.size()>HEADERS.size() || rows.size()>10001) throw new RenException("CSV超出行数或字段限制");
        }
        if(quoted) throw new RenException("CSV引号未闭合");
        if(field.length()>0 || !row.isEmpty() || closed){row.add(field.toString());rows.add(row);}
        return rows;
    }
}

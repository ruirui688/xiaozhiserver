package xiaozhi.modules.platform.service;

import java.math.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.billing.service.BillingSupport;
import xiaozhi.modules.platform.dto.PlatformRequests.Rate;
import static xiaozhi.modules.platform.service.PlatformSupport.*;

/** Prices are service allowance per base unit: token, millisecond, character. */
@Service @RequiredArgsConstructor
public class PlatformRateService {
    private final JdbcTemplate jdbc;
    private final BillingSupport json;
    private static final Set<String> METRICS=Set.of("input_tokens","output_tokens","audio_duration","billable_characters");
    public Map<String,Object> add(Rate input) {
        Map<String,Object> rates=normalize(input.metricRates()), max=normalize(input.maxUsage());
        if(!rates.keySet().equals(max.keySet()))throw new RenException("RATE_METRICS_MISMATCH");
        String service=jdbc.queryForObject("SELECT model_type FROM ai_model_config WHERE id=?",String.class,input.modelId());
        Set<String> expected=switch(BillingSupport.service(service)){case "LLM"->Set.of("input_tokens","output_tokens");case "ASR"->Set.of("audio_duration");case "TTS"->Set.of("billable_characters");default->throw new RenException("ADAPTER_ADMISSION_UNSUPPORTED");};
        if(!rates.keySet().equals(expected))throw new RenException("RATE_METRICS_MISMATCH");
        max.values().forEach(PlatformSupport::positive);
        jdbc.update("INSERT INTO ai_platform_rate(model_id,version,metric_rates_json,max_usage_json,created_at) VALUES(?,?,?,?,?)",input.modelId(),required(input.version(),64),json.json(rates),json.json(max),now());
        return latest(input.modelId());
    }
    public Map<String,Object> latest(String model) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM ai_platform_rate WHERE model_id=? ORDER BY id DESC LIMIT 1",model);
        if(rows.isEmpty())throw new RenException("RATE_UNPUBLISHED");return rows.getFirst();
    }
    public List<Map<String,Object>> list() {
        return jdbc.queryForList("SELECT model_id AS modelId,version,metric_rates_json AS metricRatesJson,max_usage_json AS maxUsageJson,created_at AS createdAt FROM ai_platform_rate ORDER BY id DESC LIMIT 500");
    }
    public static Map<String,Object> normalize(Map<String,?> values) {
        if(values==null||values.isEmpty()||!METRICS.containsAll(values.keySet()))throw new RenException("INVALID_USAGE_METRICS");
        Map<String,Object> result=new TreeMap<>();values.forEach((key,value)->result.put(key,nonnegative(value).toPlainString()));return result;
    }
    public static BigDecimal cost(Map<String,?> rates,Map<String,?> quantities) {
        if(!quantities.keySet().containsAll(rates.keySet()))throw new RenException("USAGE_INCOMPLETE");
        BigDecimal sum=BigDecimal.ZERO;
        for(String metric:rates.keySet())sum=sum.add(nonnegative(rates.get(metric)).multiply(nonnegative(quantities.get(metric))));
        return sum.setScale(9,RoundingMode.CEILING);
    }
    public static void bounded(Map<String,?> quantities,Map<String,?> maximum) {
        if(!quantities.keySet().equals(maximum.keySet()))throw new RenException("USAGE_METRICS_MISMATCH");
        quantities.forEach((key,value)->{BigDecimal q=nonnegative(value);if(q.stripTrailingZeros().scale()>0||q.compareTo(nonnegative(maximum.get(key)))>0)throw new RenException("USAGE_LIMIT_EXCEEDED");});
    }
}

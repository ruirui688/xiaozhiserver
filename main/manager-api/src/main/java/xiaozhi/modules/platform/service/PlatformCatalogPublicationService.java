package xiaozhi.modules.platform.service;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.redis.*;
import xiaozhi.modules.billing.service.BillingSupport;
import xiaozhi.modules.model.dao.ModelConfigDao;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.impl.ModelRuntimeAssembler;
import xiaozhi.modules.security.ops.*;
import xiaozhi.modules.agent.runtime.AgentRuntimeConfigRefreshEvent;
import static xiaozhi.modules.platform.service.PlatformSupport.*;

@Service @RequiredArgsConstructor
public class PlatformCatalogPublicationService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ModelConfigDao models;
    private final ModelRuntimeAssembler assembler;
    private final PlatformRuntimeService runtime;
    private final PlatformRateService rates;
    private final OpsApprovalService approvals;
    private final RedisUtils redis;
    private final ApplicationEventPublisher events;
    public Map<String,Object> preview(String id) {
        OpsAccess.require("model.catalog.publish","PLATFORM","models");
        ModelConfigEntity model=models.selectById(id);if(model==null)throw new RenException("MODEL_NOT_FOUND");
        if(!Integer.valueOf(1).equals(model.getTestStatus()))throw new RenException("MODEL_TEST_REQUIRED");
        String service=BillingSupport.service(model.getModelType());
        boolean local="ASR".equals(service)&&Set.of("fun_local","vosk","sherpa_onnx_local").contains(model.getAdapterCode());
        Map<String,Object> approved=local?assembler.buildTypedConfig(model):runtime.approved(id,service,true).config();
        if(!local)rates.latest(id);
        // HTTP serializes Long as text. Hash the same timestamp representation the approver receives.
        Map<String,Object> payload=map("modelId",id,"modelType",model.getModelType(),"adapterCode",model.getAdapterCode(),"endpointUrl",model.getEndpointUrl(),"upstreamModelId",model.getUpstreamModelId(),"runtime",approvalValue(PlatformCatalogSecrets.publicValue(approved)),"testTime",model.getTestTime()==null?null:Long.toString(model.getTestTime().getTime()));
        String version=approvals.hash(payload);
        return map("action","model.catalog.publish","targetId",id,"version",version,"payload",payload);
    }
    public Map<String,Object> publish(String id) {
        return tx.execute(status->{
            jdbc.queryForMap("SELECT id FROM ai_model_config WHERE id=? FOR UPDATE",id);
            Map<String,Object> preview=preview(id);
            return approvals.executeCurrent("model.catalog.publish",id,preview.get("version").toString(),preview.get("payload"),()->{
                jdbc.update("UPDATE ai_model_config SET is_enabled=1 WHERE id=?",id);
                redis.delete(List.of(RedisKeys.getModelConfigById(id),RedisKeys.getModelConfigById(id)+":public-v2",RedisKeys.getServerConfigKey(),RedisKeys.getServerConfigKey()+":public-v2"));
                List<String> agents=jdbc.queryForList("SELECT id FROM ai_agent WHERE llm_model_id=? OR asr_model_id=? OR tts_model_id=?",String.class,id,id,id);
                events.publishEvent(new AgentRuntimeConfigRefreshEvent(agents,"model_catalog_published"));
                return map("published",true,"modelId",id);
            });
        });
    }
    private static Object approvalValue(Object value) {
        if(value instanceof Long number)return number.toString();
        if(value instanceof Map<?,?> values){Map<String,Object> result=new LinkedHashMap<>();values.forEach((key,item)->result.put(key.toString(),approvalValue(item)));return result;}
        if(value instanceof Collection<?> values)return values.stream().map(PlatformCatalogPublicationService::approvalValue).toList();
        return value;
    }
}

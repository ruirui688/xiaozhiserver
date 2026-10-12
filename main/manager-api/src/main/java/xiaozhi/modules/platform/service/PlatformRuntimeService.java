package xiaozhi.modules.platform.service;

import java.net.URI;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.billing.service.BillingSupport;
import xiaozhi.modules.model.dao.ModelConfigDao;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.impl.ModelRuntimeAssembler;
import static xiaozhi.modules.platform.service.PlatformSupport.*;

@Service @RequiredArgsConstructor
public class PlatformRuntimeService {
    private final ModelConfigDao models;
    private final ModelRuntimeAssembler assembler;
    private static final Set<String> PUBLIC=Set.of("type","base_url","api_url","url","ws_url","endpoint_url","model_name","model","resource_id","cluster","voice","speaker","sample_rate","audio_format","format","speed","pitch","volume","temperature","top_p","max_tokens","max_completion_tokens","submit_url","query_url");
    public record Runtime(ModelConfigEntity model,String provider,Map<String,Object> config) {}
    public Runtime approved(String modelId,String service) { return approved(modelId,service,false); }
    public Runtime approved(String modelId,String service,boolean modelTest) {
        ModelConfigEntity model=models.selectById(modelId);
        if(model==null||!modelTest&&(!Integer.valueOf(1).equals(model.getIsEnabled())||!Integer.valueOf(1).equals(model.getTestStatus())))throw new RenException("MODEL_NOT_PUBLISHED");
        if(!service.equals(BillingSupport.service(model.getModelType())))throw new RenException("MODEL_SERVICE_MISMATCH");
        if("LLM".equals(service))required(model.getUpstreamModelId(),200);
        String adapter=model.getAdapterCode();
        boolean supported=switch(service){case "LLM"->"openai".equals(adapter);case "ASR"->Set.of("doubao_stream","doubao_recording").contains(adapter);case "TTS"->"huoshan_double_stream".equals(adapter);default->false;};
        if(!supported)throw new RenException("ADAPTER_ADMISSION_UNSUPPORTED");
        String endpoint=model.getEndpointUrl();
        if(endpoint==null||endpoint.isBlank())endpoint=switch(adapter){case "doubao_stream"->"wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async";case "doubao_recording"->"https://openspeech.bytedance.com/api/v3/auc/bigmodel/submit";case "huoshan_double_stream"->"wss://openspeech.bytedance.com/api/v3/tts/bidirection";default->throw new RenException("UNAPPROVED_MODEL_ENDPOINT");};String provider=validateEndpoint(endpoint,service);
        Map<String,Object> runtime=new LinkedHashMap<>();
        assembler.buildTypedConfig(model).forEach((key,value)->{if(PUBLIC.contains(key))runtime.put(key,value);});
        for(var entry:runtime.entrySet()) if(entry.getKey().endsWith("url"))validateEndpoint(String.valueOf(entry.getValue()),service);
        runtime.put("model_name",model.getUpstreamModelId());runtime.put("model",model.getUpstreamModelId());runtime.put("type",adapter);
        if("LLM".equals(service))runtime.put("base_url",endpoint);
        if("doubao_stream".equals(adapter)||"huoshan_double_stream".equals(adapter))runtime.put("ws_url",endpoint);
        if("doubao_recording".equals(adapter)) {
            runtime.put("submit_url","https://openspeech.bytedance.com/api/v3/auc/bigmodel/submit");
            runtime.put("query_url","https://openspeech.bytedance.com/api/v3/auc/bigmodel/query");
        }
        return new Runtime(model,provider,runtime);
    }
    public static String validateEndpoint(String endpoint,String service) {
        try {
            URI uri=URI.create(endpoint);String host=uri.getHost();
            if(uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||uri.getPort()!=-1&&uri.getPort()!=443)throw new IllegalArgumentException();
            if("LLM".equals(service)&&"https".equals(uri.getScheme())) {
                if("api.deepseek.com".equals(host))return "DEEPSEEK";
                if(Set.of("ark.cn-beijing.volces.com","ark.cn-shanghai.volces.com").contains(host))return "VOLCENGINE";
            }
            if(Set.of("ASR","TTS").contains(service)&&Set.of("https","wss").contains(uri.getScheme())&&"openspeech.bytedance.com".equals(host))return "VOLCENGINE";
        } catch(RuntimeException ignored) { }
        throw new RenException("UNAPPROVED_MODEL_ENDPOINT");
    }
}

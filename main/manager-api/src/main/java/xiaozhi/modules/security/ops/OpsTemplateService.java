package xiaozhi.modules.security.ops;

import java.util.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.agent.dao.AgentTemplateDao;
import xiaozhi.modules.agent.entity.AgentTemplateEntity;

@Service @RequiredArgsConstructor
public class OpsTemplateService {
    private final AgentTemplateDao templates;
    private final OpsApprovalService approvals;
    private final OpsIdentityService identities;
    private final ObjectMapper mapper;
    private static final List<String> FIELDS=List.of("templateName","agentName","systemPrompt","asrModelId","vadModelId","llmModelId","slmModelId","vllmModelId","ttsModelId","ttsVoiceId","memModelId","intentModelId","language","langCode","sort");
    public List<Map<String,Object>> list(){read();return templates.selectList(new QueryWrapper<AgentTemplateEntity>().isNull("workspace_id").orderByDesc("created_at").last("LIMIT 200")).stream().map(this::view).toList();}
    public Map<String,Object> get(String id){read();return view(official(id));}
    @Transactional public Map<String,Object> create(Map<String,Object> input){write();AgentTemplateEntity draft=from(input);draft.setId(UUID.randomUUID().toString().replace("-", ""));draft.setVersion(1);draft.setWorkspaceId(null);draft.setVisibility("OFFICIAL");draft.setReviewStatus("DRAFT");draft.setCreatedAt(new Date());draft.setUpdatedAt(new Date());templates.insert(draft);
        // MyBatis omits null insert fields. Clear legacy DB defaults in this same
        // transaction before exposing the draft or preparing approval evidence.
        int rows=templates.update(null,fields(draft).eq("id",draft.getId()).eq("version",1).eq("review_status","DRAFT"));if(rows!=1)throw new RenException("草稿保存失败");
        identities.audit(OpsAccess.employee().getEmployeeId(),"template.draft",draft.getId(),"CREATED");return view(draft);}
    @Transactional public Map<String,Object> update(String id,Map<String,Object> input){write();AgentTemplateEntity old=official(id);draft(old);AgentTemplateEntity next=from(input);next.setId(id);next.setVersion(version(old)+1);next.setUpdatedAt(new Date());
        int rows=templates.update(null,fields(next).set("version",next.getVersion()).set("updated_at",next.getUpdatedAt()).eq("id",id).eq("version",version(old)).eq("review_status","DRAFT"));if(rows!=1)throw new RenException("草稿已变化，请刷新");identities.audit(OpsAccess.employee().getEmployeeId(),"template.edit",id,"UPDATED");return view(official(id));}
    @Transactional public Map<String,Object> cloneDraft(String id){write();AgentTemplateEntity source=official(id);Map<String,Object> payload=payload(source);payload.put("templateName",Objects.toString(source.getTemplateName(),source.getAgentName())+"（新版本）");return create(payload);}
    public Map<String,Object> prepare(String id){write();AgentTemplateEntity item=official(id);draft(item);return Map.of("action","official.resource.publish","targetId",id,"version",String.valueOf(version(item)),"payload",payload(item));}
    public void publish(String id){AgentTemplateEntity item=official(id);draft(item);int version=version(item);approvals.executeCurrent("official.resource.publish",id,String.valueOf(version),payload(item),()->{
        int rows=templates.update(null,new UpdateWrapper<AgentTemplateEntity>().eq("id",id).eq("version",version).eq("review_status","DRAFT").set("review_status","APPROVED").set("published_at",new Date()).set("reviewed_at",new Date()));
        if(rows!=1)throw new RenException("草稿已变化，请重新审批");return null;
    });}
    private AgentTemplateEntity from(Map<String,Object> input){Map<String,Object> allowed=new LinkedHashMap<>();for(String field:FIELDS)if(input.containsKey(field)){Object value=input.get(field);if(optional(field)&&value instanceof String text&&text.isBlank())value=null;allowed.put(field,value);}AgentTemplateEntity entity=mapper.convertValue(allowed,AgentTemplateEntity.class);
        if(entity.getAgentName()==null||entity.getAgentName().isBlank()||entity.getAgentName().length()>64||entity.getSystemPrompt()==null||entity.getSystemPrompt().length()>100000)throw new RenException("模板名称和提示词无效");if(entity.getTemplateName()==null)entity.setTemplateName(entity.getAgentName());if(entity.getSort()==null)entity.setSort(100);return entity;}
    private static boolean optional(String field){return field.endsWith("ModelId")||List.of("ttsVoiceId","language","langCode").contains(field);}
    private UpdateWrapper<AgentTemplateEntity> fields(AgentTemplateEntity entity){
        UpdateWrapper<AgentTemplateEntity> update=new UpdateWrapper<>();
        // Column names come only from the fixed editable-field whitelist, and
        // SET must include null so clearing an optional selection is persistent.
        payload(entity).forEach((field,value)->update.set(field.replaceAll("([a-z])([A-Z])","$1_$2").toLowerCase(Locale.ROOT),value));
        return update;
    }
    private AgentTemplateEntity official(String id){AgentTemplateEntity item=templates.selectById(id);if(item==null||item.getWorkspaceId()!=null)throw new RenException("官方模板不存在");return item;}
    private Map<String,Object> payload(AgentTemplateEntity entity){Map<String,Object> all=mapper.convertValue(entity,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});Map<String,Object> result=new LinkedHashMap<>();for(String field:FIELDS)result.put(field,all.get(field));return result;}
    private Map<String,Object> view(AgentTemplateEntity entity){Map<String,Object> result=payload(entity);result.put("id",entity.getId());result.put("version",version(entity));result.put("reviewStatus",entity.getReviewStatus());return result;}
    private static int version(AgentTemplateEntity entity){return entity.getVersion()==null?1:entity.getVersion();}
    private static void draft(AgentTemplateEntity entity){if(!"DRAFT".equals(entity.getReviewStatus()))throw new RenException("已发布模板不可原地修改，请克隆新草稿");}
    private static void read(){OpsAccess.require("official.resource.read","PLATFORM","official-resources");}
    private static void write(){OpsAccess.require("official.resource.write","PLATFORM","official-resources");}
}

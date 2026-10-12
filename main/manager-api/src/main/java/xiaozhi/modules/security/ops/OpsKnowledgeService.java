package xiaozhi.modules.security.ops;

import java.nio.charset.StandardCharsets;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.model.dao.ModelConfigDao;

/** Public text/QA drafts have no source workspace or access to private datasets. */
@Service @RequiredArgsConstructor
public class OpsKnowledgeService {
    private final JdbcTemplate jdbc;
    private final ModelConfigDao models;
    private final OpsApprovalService approvals;
    private final OpsIdentityService identities;
    public record Entry(@Size(max=20000) String question,@Size(max=50000) String answer,@Size(max=100000) String content) {}
    public record Draft(@NotBlank @Size(max=100) String name,@Size(max=2000) String description,
                        @NotBlank String ragModelId,@NotNull @Size(min=1,max=200) List<@Valid Entry> entries) {}
    public List<Map<String,Object>> list(){read();return jdbc.queryForList("SELECT id,dataset_id AS datasetId,name,description,rag_model_id AS ragModelId,scope,publication_version AS version,chunk_count AS chunkCount FROM ai_rag_dataset WHERE workspace_id IS NULL AND scope IN ('OFFICIAL','OFFICIAL_DRAFT') ORDER BY created_at DESC LIMIT 200");}
    public Map<String,Object> get(String id){read();return view(official(id,false));}
    @Transactional public Map<String,Object> create(Draft input){write();validate(input);String id=UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO ai_rag_dataset(id,dataset_id,name,description,rag_model_id,scope,permission,chunk_method,parser_config,chunk_count,document_count,token_num,status,publication_version) VALUES(?,?,?,?,?,'OFFICIAL_DRAFT','team','naive','{}',0,0,0,0,1)",id,id,input.name(),input.description(),input.ragModelId());
        replaceEntries(id,input.entries());identities.audit(OpsAccess.employee().getEmployeeId(),"knowledge.draft",id,"CREATED");return view(official(id,false));}
    @Transactional public Map<String,Object> update(String id,Draft input){write();var row=official(id,true);draft(row);validate(input);
        jdbc.update("UPDATE ai_rag_dataset SET name=?,description=?,rag_model_id=?,publication_version=publication_version+1 WHERE id=?",input.name(),input.description(),input.ragModelId(),id);
        replaceEntries(id,input.entries());identities.audit(OpsAccess.employee().getEmployeeId(),"knowledge.edit",id,"UPDATED");return view(official(id,false));}
    @Transactional public Map<String,Object> cloneDraft(String id){write();var row=official(id,true);List<Entry> entries=entries(id).stream().map(e->new Entry((String)e.get("question"),(String)e.get("answer"),(String)e.get("content"))).toList();
        return create(new Draft(row.get("name")+"（新版本）",(String)row.get("description"),row.get("ragModelId").toString(),entries));}
    public Map<String,Object> prepare(String id){write();var row=official(id,false);draft(row);return proposal(row);}
    @Transactional public void publish(String id){write();var row=official(id,true);draft(row);var payload=payload(row);
        approvals.executeCurrent("official.resource.publish",id,String.valueOf(row.get("version")),payload,()->{
            jdbc.update("UPDATE ai_rag_dataset SET scope='OFFICIAL',status=1 WHERE id=? AND scope='OFFICIAL_DRAFT'",id);return null;
        });}
    private Map<String,Object> proposal(Map<String,Object> row){return Map.of("action","official.resource.publish","targetId",row.get("id"),"version",String.valueOf(row.get("version")),"payload",payload(row));}
    private Map<String,Object> view(Map<String,Object> row){var result=payload(row);result.put("id",row.get("id"));result.put("datasetId",row.get("id"));result.put("version",row.get("version"));result.put("scope",row.get("scope"));result.put("reviewStatus","OFFICIAL".equals(row.get("scope"))?"APPROVED":"DRAFT");return result;}
    private Map<String,Object> payload(Map<String,Object> row){Map<String,Object> result=new LinkedHashMap<>();for(String field:List.of("name","description","ragModelId"))result.put(field,row.get(field));result.put("entries",entries(row.get("id").toString()));return result;}
    private Map<String,Object> official(String id,boolean lock){var rows=jdbc.queryForList("SELECT id,name,description,rag_model_id AS ragModelId,scope,publication_version AS version FROM ai_rag_dataset WHERE id=? AND workspace_id IS NULL AND scope IN ('OFFICIAL','OFFICIAL_DRAFT')"+(lock?" FOR UPDATE":""),id);if(rows.isEmpty())throw new RenException("官方知识库不存在");return rows.getFirst();}
    private List<Map<String,Object>> entries(String id){return jdbc.queryForList("SELECT question,answer,content FROM ai_rag_knowledge_chunk WHERE dataset_id=? ORDER BY sort_order,id",id);}
    private void replaceEntries(String id,List<Entry> entries){
        jdbc.update("DELETE FROM ai_rag_knowledge_chunk WHERE dataset_id=?",id);jdbc.update("DELETE FROM ai_rag_knowledge_document WHERE dataset_id=?",id);
        String document=UUID.randomUUID().toString().replace("-", "");long bytes=0,tokens=0;
        int index=0;for(Entry entry:entries){String content=content(entry);bytes+=content.getBytes(StandardCharsets.UTF_8).length;long count=Math.max(1,content.length()/2);tokens+=count;String chunk=UUID.randomUUID().toString().replace("-", "");
            jdbc.update("INSERT INTO ai_rag_knowledge_chunk(id,dataset_id,document_id,chunk_id,content,question,answer,sort_order,token_count,enabled,edit_version) VALUES(?,?,?,?,?,?,?,?,?,1,1)",chunk,id,document,chunk,content,entry.question(),entry.answer(),index++,count);}
        jdbc.update("INSERT INTO ai_rag_knowledge_document(id,dataset_id,document_id,name,size,type,chunk_method,parser_config,status,run,progress,source_type,chunk_count,token_count,enabled,content_version,index_dirty) VALUES(?,?,?,'官方知识条目',?,'txt','naive','{}','1','DONE',1,'local',?,?,1,1,0)",document,id,document,bytes,entries.size(),tokens);
        jdbc.update("UPDATE ai_rag_dataset SET document_count=1,chunk_count=?,token_num=? WHERE id=?",entries.size(),tokens,id);
    }
    private void validate(Draft input){
        if(input==null||input.name()==null||input.name().isBlank()||input.name().length()>100||input.entries()==null||input.entries().isEmpty()||input.entries().size()>200)throw new RenException("名称和知识条目不能为空");
        var model=models.selectById(input.ragModelId());String adapter=model==null?null:model.getAdapterCode();
        if(model!=null&&(adapter==null||adapter.isBlank())&&model.getConfigJson()!=null)adapter=model.getConfigJson().getStr("type");
        if(model==null||!"RAG".equalsIgnoreCase(model.getModelType())||!"local_knowledge".equals(adapter))throw new RenException("官方知识库草案仅支持本地知识库适配器");
        long total=0;for(Entry entry:input.entries()){if(entry==null)throw new RenException("知识条目无效");String text=content(entry);if(text.isBlank()||text.length()>100000)throw new RenException("知识条目无效");total+=text.length();}if(total>500000)throw new RenException("知识库草案内容过大");
    }
    private static String content(Entry entry){return entry.content()!=null&&!entry.content().isBlank()?entry.content():Objects.toString(entry.question(),"")+"\n"+Objects.toString(entry.answer(),"");}
    private static void draft(Map<String,Object> row){if(!"OFFICIAL_DRAFT".equals(row.get("scope")))throw new RenException("已发布知识库不可修改，请克隆新草稿");}
    private static void read(){OpsAccess.require("official.resource.read","PLATFORM","official-resources");}
    private static void write(){OpsAccess.require("official.resource.write","PLATFORM","official-resources");}
}

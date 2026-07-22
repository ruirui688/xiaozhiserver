package xiaozhi.modules.knowledge.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import com.fasterxml.jackson.databind.ObjectMapper;

import cn.hutool.json.JSONObject;
import xiaozhi.common.redis.RedisUtils;
import xiaozhi.common.user.UserDetail;
import xiaozhi.modules.knowledge.dao.DocumentDao;
import xiaozhi.modules.knowledge.dao.KnowledgeBaseDao;
import xiaozhi.modules.knowledge.dao.KnowledgeChunkDao;
import xiaozhi.modules.knowledge.dto.KnowledgeBaseDTO;
import xiaozhi.modules.knowledge.dto.dataset.DatasetDTO;
import xiaozhi.modules.knowledge.entity.KnowledgeBaseEntity;
import xiaozhi.modules.knowledge.rag.KnowledgeBaseAdapter;
import xiaozhi.modules.knowledge.rag.KnowledgeBaseAdapterFactory;
import xiaozhi.modules.model.dao.ModelConfigDao;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.ModelConfigService;
import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.modules.workspace.entity.WorkspaceEntity;
import xiaozhi.modules.workspace.service.WorkspacePermissionService;
import xiaozhi.modules.workspace.service.WorkspaceService;

class KnowledgeBaseServiceImplTest {
    @Test
    void saveForwardsRagFieldsAndPreservesFullLocalParserConfig() {
        KnowledgeBaseDao knowledgeBaseDao = mock(KnowledgeBaseDao.class);
        KnowledgeChunkDao chunkDao = mock(KnowledgeChunkDao.class);
        DocumentDao documentDao = mock(DocumentDao.class);
        ModelConfigService modelConfigService = mock(ModelConfigService.class);
        ModelConfigDao modelConfigDao = mock(ModelConfigDao.class);
        RedisUtils redisUtils = mock(RedisUtils.class);
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        WorkspacePermissionService permissionService = mock(WorkspacePermissionService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        KnowledgeBaseServiceImpl service = new KnowledgeBaseServiceImpl(
                knowledgeBaseDao, chunkDao, documentDao, modelConfigService, modelConfigDao,
                redisUtils, workspaceService, permissionService, objectMapper);

        ModelConfigEntity ragModel = new ModelConfigEntity();
        ragModel.setConfigJson(new JSONObject(Map.of("type", "local_knowledge")));
        when(modelConfigService.getModelByIdFromCache("rag-local")).thenReturn(ragModel);
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId("workspace-1");
        when(workspaceService.getCurrentWorkspaceEntity()).thenReturn(workspace);

        DatasetDTO.ParserConfig remoteDefaults = new DatasetDTO.ParserConfig();
        remoteDefaults.setChunkTokenNum(256);
        DatasetDTO.InfoVO ragResponse = new DatasetDTO.InfoVO();
        ragResponse.setId("dataset-1");
        ragResponse.setParserConfig(remoteDefaults);
        KnowledgeBaseAdapter adapter = mock(KnowledgeBaseAdapter.class);
        when(adapter.createDataset(any())).thenReturn(ragResponse);

        UserDetail user = new UserDetail();
        user.setUsername("tester");
        String parserConfig = "{\"chunk_token_num\":768,\"llm_enabled\":true,"
                + "\"llm_model_id\":\"llm-1\",\"retrieval_config\":{\"semantic_top_k\":12}}";
        KnowledgeBaseDTO request = new KnowledgeBaseDTO();
        request.setName("产品资料");
        request.setDescription("产品知识");
        request.setRagModelId("rag-local");
        request.setParserConfig(parserConfig);

        try (MockedStatic<SecurityUser> security = mockStatic(SecurityUser.class);
                MockedStatic<KnowledgeBaseAdapterFactory> factory = mockStatic(KnowledgeBaseAdapterFactory.class)) {
            security.when(SecurityUser::getUser).thenReturn(user);
            factory.when(() -> KnowledgeBaseAdapterFactory.getAdapter("local_knowledge", Map.of("type", "local_knowledge")))
                    .thenReturn(adapter);

            service.save(request);
        }

        ArgumentCaptor<DatasetDTO.CreateReq> createCaptor = ArgumentCaptor.forClass(DatasetDTO.CreateReq.class);
        verify(adapter).createDataset(createCaptor.capture());
        assertEquals(768, createCaptor.getValue().getParserConfig().getChunkTokenNum());

        ArgumentCaptor<KnowledgeBaseEntity> entityCaptor = ArgumentCaptor.forClass(KnowledgeBaseEntity.class);
        verify(knowledgeBaseDao).insert(entityCaptor.capture());
        String persisted = entityCaptor.getValue().getParserConfig();
        assertEquals(parserConfig, persisted);
        assertTrue(persisted.contains("llm_model_id"));
        assertTrue(persisted.contains("retrieval_config"));
        verify(permissionService).requireWrite("workspace-1");
    }
}

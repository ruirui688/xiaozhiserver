package xiaozhi.modules.knowledge.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import cn.hutool.json.JSONObject;
import xiaozhi.common.exception.RenException;
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
    void rejectsLocalKnowledgeWithoutSemanticChunkingModel() {
        KnowledgeBaseServiceImpl service = new KnowledgeBaseServiceImpl(
                mock(KnowledgeBaseDao.class),
                mock(KnowledgeChunkDao.class),
                mock(DocumentDao.class),
                mock(ModelConfigService.class),
                mock(ModelConfigDao.class),
                mock(RedisUtils.class),
                mock(WorkspaceService.class),
                mock(WorkspacePermissionService.class),
                new ObjectMapper());
        KnowledgeBaseDTO request = new KnowledgeBaseDTO();
        request.setParserConfig("{}");

        RenException error = assertThrows(RenException.class,
                () -> ReflectionTestUtils.invokeMethod(service, "enforceLocalSemanticChunkingConfig",
                        request, Map.of("type", "local_knowledge")));

        assertTrue(error.getMessage().contains("必须选择大模型语义切片模型"));
    }

    @Test
    void saveForwardsRagFieldsAndPreservesFullLocalParserConfig() throws Exception {
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
        ModelConfigEntity llmModel = new ModelConfigEntity();
        llmModel.setId("llm-1");
        llmModel.setModelType("LLM");
        llmModel.setAdapterCode("openai");
        llmModel.setIsEnabled(1);
        llmModel.setTestStatus(1);
        llmModel.setConfigJson(new JSONObject(Map.of(
                "type", "openai",
                "base_url", "https://example.invalid/v1",
                "model_name", "semantic-chunker")));
        when(modelConfigService.getModelByIdFromCache("llm-1")).thenReturn(llmModel);
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
        assertEquals(objectMapper.readTree(parserConfig), objectMapper.readTree(persisted));
        assertTrue(persisted.contains("llm_model_id"));
        assertTrue(persisted.contains("retrieval_config"));
        verify(permissionService).requireWrite("workspace-1");
    }
}

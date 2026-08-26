package xiaozhi.modules.agent.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import xiaozhi.modules.agent.dto.AgentTemplateApplyDTO;
import xiaozhi.modules.agent.dto.AgentFeishuTemplateDTO;
import xiaozhi.modules.agent.dto.AgentTemplateSaveAsDTO;
import xiaozhi.modules.agent.dto.AgentTemplateReviewDTO;
import xiaozhi.modules.agent.dto.AgentTemplateSnapshotDTO;
import xiaozhi.modules.agent.dto.AgentUpdateDTO;
import xiaozhi.modules.agent.dto.ContextProviderDTO;
import xiaozhi.modules.agent.entity.AgentTemplateEntity;
import xiaozhi.modules.agent.service.AgentService;
import xiaozhi.modules.agent.service.AgentFeishuService;
import xiaozhi.modules.agent.service.AgentTagService;
import xiaozhi.modules.agent.service.AgentTemplateService;
import xiaozhi.modules.agent.service.AgentVoicePrintService;
import xiaozhi.modules.agent.skill.service.AgentSemanticSkillService;
import xiaozhi.modules.agent.vo.AgentInfoVO;
import xiaozhi.modules.agent.vo.AgentTemplateApplyResultVO;
import xiaozhi.modules.correctword.dao.CorrectWordFileDao;
import xiaozhi.modules.knowledge.service.KnowledgeBaseService;
import xiaozhi.modules.model.dao.ModelProviderDao;
import xiaozhi.modules.model.service.ModelConfigService;
import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.modules.timbre.service.TimbreService;
import xiaozhi.modules.workspace.service.WorkspacePermissionService;
import xiaozhi.modules.workspace.service.OfficialResourcePermissionService;

class AgentWorkspaceTemplateServiceImplTest {
    private AgentTemplateService templateService;
    private AgentService agentService;
    private AgentTagService tagService;
    private AgentSemanticSkillService skillService;
    private AgentVoicePrintService voicePrintService;
    private AgentFeishuService feishuService;
    private WorkspacePermissionService permissionService;
    private OfficialResourcePermissionService officialPermissionService;
    private AgentWorkspaceTemplateServiceImpl service;

    @BeforeEach
    void setUp() {
        templateService = mock(AgentTemplateService.class);
        agentService = mock(AgentService.class);
        tagService = mock(AgentTagService.class);
        skillService = mock(AgentSemanticSkillService.class);
        voicePrintService = mock(AgentVoicePrintService.class);
        feishuService = mock(AgentFeishuService.class);
        permissionService = mock(WorkspacePermissionService.class);
        officialPermissionService = mock(OfficialResourcePermissionService.class);
        service = new AgentWorkspaceTemplateServiceImpl(
                templateService,
                agentService,
                tagService,
                skillService,
                voicePrintService,
                feishuService,
                mock(ModelConfigService.class),
                mock(ModelProviderDao.class),
                mock(KnowledgeBaseService.class),
                mock(CorrectWordFileDao.class),
                mock(TimbreService.class),
                permissionService,
                officialPermissionService,
                mock(org.springframework.context.ApplicationEventPublisher.class));
    }

    @Test
    void approvingMarketplaceTemplateRemovesWorkspacePrivateBindings() {
        AgentUpdateDTO config = new AgentUpdateDTO();
        config.setCorrectWordFileIds(List.of("private-words"));
        AgentTemplateSnapshotDTO snapshot = new AgentTemplateSnapshotDTO();
        snapshot.setSchemaVersion(2);
        snapshot.setConfig(config);
        snapshot.setVoicePrintIds(List.of("private-voice"));
        snapshot.setFeishuConfig(new AgentFeishuTemplateDTO());
        AgentTemplateEntity template = new AgentTemplateEntity();
        template.setId("template-1");
        template.setWorkspaceId("workspace-a");
        template.setReviewStatus("PENDING");
        template.setSnapshotJson(snapshot);
        template.setVersion(1);
        when(templateService.getById("template-1")).thenReturn(template);

        AgentTemplateReviewDTO request = new AgentTemplateReviewDTO();
        request.setDecision("APPROVED");
        try (MockedStatic<SecurityUser> security = mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUserId).thenReturn(7L);
            service.review("template-1", request);
        }

        ArgumentCaptor<AgentTemplateEntity> captor = ArgumentCaptor.forClass(AgentTemplateEntity.class);
        verify(templateService).updateById(captor.capture());
        AgentTemplateSnapshotDTO published = captor.getValue().getPublicSnapshotJson();
        assertTrue(published.getVoicePrintIds().isEmpty());
        assertTrue(published.getConfig().getCorrectWordFileIds().isEmpty());
        assertEquals(null, published.getFeishuConfig());
        assertEquals("PUBLIC", captor.getValue().getVisibility());
        assertEquals("private-voice", captor.getValue().getSnapshotJson().getVoicePrintIds().get(0));
    }

    @Test
    void saveAsRemovesSensitiveContextHeadersWithoutSavingTheAgentDraft() {
        AgentInfoVO source = agent("agent-a", "workspace-a");
        when(agentService.checkAgentPermission("agent-a", 7L)).thenReturn(true);
        when(agentService.getAgentById("agent-a")).thenReturn(source);
        when(skillService.list("agent-a")).thenReturn(List.of());
        when(voicePrintService.getBoundVoicePrintIds("agent-a")).thenReturn(List.of("voice-a"));
        when(voicePrintService.getWorkspaceIdByVoicePrintId("voice-a")).thenReturn("workspace-a");
        AgentFeishuTemplateDTO feishu = new AgentFeishuTemplateDTO();
        feishu.setAppId("cli_test");
        when(feishuService.exportForTemplate(
                org.mockito.ArgumentMatchers.eq("agent-a"), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(feishu);
        doAnswer(invocation -> {
            AgentTemplateEntity saved = invocation.getArgument(0);
            saved.setVersion(1);
            return true;
        }).when(templateService).save(any(AgentTemplateEntity.class));

        ContextProviderDTO context = new ContextProviderDTO();
        context.setUrl("https://context.example.test");
        LinkedHashMap<String, Object> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer should-not-persist");
        headers.put("X-Trace-Id", "safe-value");
        context.setHeaders(headers);
        AgentUpdateDTO config = new AgentUpdateDTO();
        config.setAgentName("展会助手");
        config.setContextProviders(List.of(context));
        AgentTemplateSaveAsDTO request = new AgentTemplateSaveAsDTO();
        request.setTemplateName("展会模板");
        request.setConfig(config);

        try (MockedStatic<SecurityUser> security = mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUserId).thenReturn(7L);
            var result = service.saveAs("agent-a", request);

            assertEquals(1, result.getWarnings().size());
        }

        ArgumentCaptor<AgentTemplateEntity> captor = ArgumentCaptor.forClass(AgentTemplateEntity.class);
        verify(templateService).save(captor.capture());
        ContextProviderDTO savedContext = captor.getValue().getSnapshotJson()
                .getConfig().getContextProviders().get(0);
        assertFalse(savedContext.getHeaders().containsKey("Authorization"));
        assertEquals("safe-value", savedContext.getHeaders().get("X-Trace-Id"));
        assertEquals(List.of("voice-a"), captor.getValue().getSnapshotJson().getVoicePrintIds());
        assertEquals("cli_test", captor.getValue().getSnapshotJson().getFeishuConfig().getAppId());
        verify(agentService, never()).updateAgentById(any(), any(AgentUpdateDTO.class), any(Boolean.class));
        verify(permissionService).requireWrite("workspace-a");
    }

    @Test
    void applyRejectsTemplateFromAnotherWorkspaceBeforeAnyWrite() {
        AgentInfoVO target = agent("agent-a", "workspace-a");
        when(agentService.checkAgentPermission("agent-a", 7L)).thenReturn(true);
        when(agentService.getAgentById("agent-a")).thenReturn(target);
        AgentTemplateEntity foreign = new AgentTemplateEntity();
        foreign.setId("template-b");
        foreign.setWorkspaceId("workspace-b");
        foreign.setVersion(1);
        when(templateService.getById("template-b")).thenReturn(foreign);
        AgentTemplateApplyDTO request = new AgentTemplateApplyDTO();
        request.setExpectedVersion(1);

        try (MockedStatic<SecurityUser> security = mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUserId).thenReturn(7L);
            assertThrows(RuntimeException.class, () -> service.apply("agent-a", "template-b", request));
        }

        verify(agentService, never()).updateAgentById(any(), any(AgentUpdateDTO.class), any(Boolean.class));
        verify(tagService, never()).saveAgentTags(any(), any(), any());
    }

    @Test
    void applySkipsUnavailableModelAndReturnsTheWarning() {
        AgentInfoVO target = agent("agent-a", "workspace-a");
        target.setLlmModelId("LLM_current");
        when(agentService.checkAgentPermission("agent-a", 7L)).thenReturn(true);
        when(agentService.getAgentById("agent-a")).thenReturn(target);
        when(tagService.getTagsByAgentId("agent-a")).thenReturn(List.of());

        AgentUpdateDTO config = new AgentUpdateDTO();
        config.setAgentName("模板角色");
        config.setLlmModelId("LLM_deleted");
        config.setFunctions(List.of());
        config.setCorrectWordFileIds(List.of());
        config.setContextProviders(List.of());
        AgentTemplateSnapshotDTO snapshot = new AgentTemplateSnapshotDTO();
        snapshot.setConfig(config);
        AgentTemplateEntity template = new AgentTemplateEntity();
        template.setId("template-a");
        template.setWorkspaceId("workspace-a");
        template.setVersion(1);
        template.setSnapshotJson(snapshot);
        when(templateService.getById("template-a")).thenReturn(template);

        AgentTemplateApplyDTO request = new AgentTemplateApplyDTO();
        request.setExpectedVersion(1);
        AgentTemplateApplyResultVO result;
        try (MockedStatic<SecurityUser> security = mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUserId).thenReturn(7L);
            result = service.apply("agent-a", "template-a", request);
        }

        ArgumentCaptor<AgentUpdateDTO> updateCaptor = ArgumentCaptor.forClass(AgentUpdateDTO.class);
        verify(agentService).updateAgentById(org.mockito.ArgumentMatchers.eq("agent-a"), updateCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(false));
        assertEquals(null, updateCaptor.getValue().getLlmModelId());
        assertTrue(result.getSkippedItems().stream().anyMatch(item -> item.contains("LLM_deleted")));
        verify(voicePrintService).replaceBindings("agent-a", List.of(), 7L);
        verify(feishuService).replaceFromTemplate("agent-a", "template-a", null, 7L);
    }

    @Test
    void applyPreservesExplicitBlankModelSoTheTargetCanBeCleared() {
        AgentInfoVO target = agent("agent-a", "workspace-a");
        target.setVllmModelId("VLLM_current");
        when(agentService.checkAgentPermission("agent-a", 7L)).thenReturn(true);
        when(agentService.getAgentById("agent-a")).thenReturn(target);
        when(tagService.getTagsByAgentId("agent-a")).thenReturn(List.of());

        AgentUpdateDTO config = new AgentUpdateDTO();
        config.setVllmModelId("");
        config.setFunctions(List.of());
        config.setCorrectWordFileIds(List.of());
        config.setContextProviders(List.of());
        AgentTemplateSnapshotDTO snapshot = new AgentTemplateSnapshotDTO();
        snapshot.setConfig(config);
        AgentTemplateEntity template = new AgentTemplateEntity();
        template.setId("template-a");
        template.setWorkspaceId("workspace-a");
        template.setVersion(1);
        template.setSnapshotJson(snapshot);
        when(templateService.getById("template-a")).thenReturn(template);
        AgentTemplateApplyDTO request = new AgentTemplateApplyDTO();
        request.setExpectedVersion(1);

        try (MockedStatic<SecurityUser> security = mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUserId).thenReturn(7L);
            service.apply("agent-a", "template-a", request);
        }

        ArgumentCaptor<AgentUpdateDTO> updateCaptor = ArgumentCaptor.forClass(AgentUpdateDTO.class);
        verify(agentService).updateAgentById(org.mockito.ArgumentMatchers.eq("agent-a"), updateCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(false));
        assertEquals("", updateCaptor.getValue().getVllmModelId());
    }

    @Test
    void applySkipsVoicePrintFromAnotherWorkspace() {
        AgentInfoVO target = agent("agent-a", "workspace-a");
        when(agentService.checkAgentPermission("agent-a", 7L)).thenReturn(true);
        when(agentService.getAgentById("agent-a")).thenReturn(target);
        when(tagService.getTagsByAgentId("agent-a")).thenReturn(List.of());
        when(voicePrintService.getWorkspaceIdByVoicePrintId("voice-b"))
                .thenReturn("workspace-b");

        AgentTemplateSnapshotDTO snapshot = new AgentTemplateSnapshotDTO();
        snapshot.setConfig(new AgentUpdateDTO());
        snapshot.setVoicePrintIds(List.of("voice-b"));
        AgentTemplateEntity template = new AgentTemplateEntity();
        template.setId("template-a");
        template.setWorkspaceId("workspace-a");
        template.setVersion(1);
        template.setSnapshotJson(snapshot);
        when(templateService.getById("template-a")).thenReturn(template);
        AgentTemplateApplyDTO request = new AgentTemplateApplyDTO();
        request.setExpectedVersion(1);

        AgentTemplateApplyResultVO result;
        try (MockedStatic<SecurityUser> security = mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUserId).thenReturn(7L);
            result = service.apply("agent-a", "template-a", request);
        }

        verify(voicePrintService).replaceBindings("agent-a", List.of(), 7L);
        assertTrue(result.getSkippedItems().stream().anyMatch(item -> item.contains("voice-b")));
    }

    @Test
    void applySupportsLegacySystemTemplateWithoutReplacingWorkspaceTagsOrSkills() {
        AgentInfoVO target = agent("agent-a", "workspace-a");
        when(agentService.checkAgentPermission("agent-a", 7L)).thenReturn(true);
        when(agentService.getAgentById("agent-a")).thenReturn(target);
        when(tagService.getTagsByAgentId("agent-a")).thenReturn(List.of());

        AgentTemplateEntity template = new AgentTemplateEntity();
        template.setId("system-template-a");
        template.setVersion(1);
        template.setAgentName("系统角色");
        template.setSystemPrompt("系统模板提示词");
        when(templateService.getById("system-template-a")).thenReturn(template);
        AgentTemplateApplyDTO request = new AgentTemplateApplyDTO();
        request.setExpectedVersion(1);

        try (MockedStatic<SecurityUser> security = mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUserId).thenReturn(7L);
            service.apply("agent-a", "system-template-a", request);
        }

        ArgumentCaptor<AgentUpdateDTO> updateCaptor = ArgumentCaptor.forClass(AgentUpdateDTO.class);
        verify(agentService).updateAgentById(org.mockito.ArgumentMatchers.eq("agent-a"), updateCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(false));
        assertEquals("系统模板提示词", updateCaptor.getValue().getSystemPrompt());
        verify(tagService, never()).saveAgentTags(any(), any(), any());
        verify(skillService, never()).replaceAll(any(), any(), any(), any(Boolean.class));
        verify(voicePrintService, never()).replaceBindings(any(), any(), any());
        verify(feishuService, never()).replaceFromTemplate(any(), any(), any(), any());
    }

    private AgentInfoVO agent(String id, String workspaceId) {
        AgentInfoVO agent = new AgentInfoVO();
        agent.setId(id);
        agent.setWorkspaceId(workspaceId);
        agent.setCorrectWordFileIds(List.of());
        return agent;
    }
}

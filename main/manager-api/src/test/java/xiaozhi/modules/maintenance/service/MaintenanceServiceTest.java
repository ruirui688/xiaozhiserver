package xiaozhi.modules.maintenance.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.AsyncTaskExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.maintenance.dao.MaintenancePolicyDao;
import xiaozhi.modules.maintenance.dao.MaintenanceRunDao;
import xiaozhi.modules.maintenance.dto.MaintenancePolicyRequest;
import xiaozhi.modules.maintenance.dto.HarnessMaintenanceRequest;
import xiaozhi.modules.maintenance.dto.HarnessMaintenanceResponse;
import xiaozhi.modules.maintenance.dto.HarnessModelConfig;
import xiaozhi.modules.maintenance.entity.MaintenancePolicyEntity;
import xiaozhi.modules.maintenance.entity.MaintenanceRunEntity;

class MaintenanceServiceTest {
    @Test
    void knowledgeAutoModeRequiresTrustedDomain() {
        MaintenanceService service = service(mock(MaintenancePolicyDao.class));
        MaintenancePolicyRequest request = request("DISCOVERY_AUTO");

        RenException error = assertThrows(RenException.class,
                () -> service.savePolicy("KNOWLEDGE", "kb-1", "ws-1", request));

        assertEquals("自动采纳模式必须配置可信域名白名单", error.getMessage());
    }

    @Test
    void memoryPolicyCannotEnableWebDomains() {
        MaintenanceService service = service(mock(MaintenancePolicyDao.class));
        MaintenancePolicyRequest request = request("REVIEW");
        request.setTrustedDomains(List.of("example.com"));

        RenException error = assertThrows(RenException.class,
                () -> service.savePolicy("MEMORY", "agent-1", "ws-1", request));

        assertEquals("记忆维护禁止配置或使用网页来源", error.getMessage());
    }

    @Test
    void policyIsScopedToOneResourceAndPersistsBudgets() {
        MaintenancePolicyDao dao = mock(MaintenancePolicyDao.class);
        when(dao.selectOne(any())).thenReturn(null);
        MaintenanceService service = service(dao);
        MaintenancePolicyRequest request = request("DISCOVERY_REVIEW");
        request.setTopics(List.of("版本公告"));
        request.setMaxProposals(4);
        request.setMaxMonthlyRuns(12);
        request.setModelConfigId("deepseek-model");
        request.setCustomPrompt("只采用当前版本官方公告");
        request.setEnabled(true);

        service.savePolicy("KNOWLEDGE", "kb-1", "ws-1", request);

        ArgumentCaptor<MaintenancePolicyEntity> captor = ArgumentCaptor.forClass(MaintenancePolicyEntity.class);
        verify(dao).insert(captor.capture());
        assertEquals("kb-1", captor.getValue().getResourceId());
        assertEquals("KNOWLEDGE", captor.getValue().getResourceType());
        assertEquals(4, captor.getValue().getMaxProposals());
        assertEquals(12, captor.getValue().getMaxMonthlyRuns());
        assertEquals("deepseek-model", captor.getValue().getModelConfigId());
        assertEquals("只采用当前版本官方公告", captor.getValue().getCustomPrompt());
        assertEquals(1, captor.getValue().getEnabled());
    }

    @Test
    void executePassesSavedPromptAndCurrentConversationMessageToHarness() {
        MaintenancePolicyDao policyDao = mock(MaintenancePolicyDao.class);
        MaintenanceRunDao runDao = mock(MaintenanceRunDao.class);
        MaintenanceSnapshotService snapshots = mock(MaintenanceSnapshotService.class);
        MaintenanceProposalService proposals = mock(MaintenanceProposalService.class);
        MaintenanceModelResolver resolver = mock(MaintenanceModelResolver.class);
        HarnessMaintenanceGateway gateway = mock(HarnessMaintenanceGateway.class);
        MaintenanceRunEntity run = new MaintenanceRunEntity();
        run.setId("run-1");
        run.setPolicyId("policy-1");
        run.setConversationId("conversation-1");
        run.setResourceType("KNOWLEDGE");
        run.setResourceId("kb-1");
        run.setStatus("QUEUED");
        run.setUserInstruction("只检查 18.1 当前赛季");
        MaintenancePolicyEntity policy = new MaintenancePolicyEntity();
        policy.setId("policy-1");
        policy.setEnabled(1);
        policy.setResourceType("KNOWLEDGE");
        policy.setResourceId("kb-1");
        policy.setMaxProposals(4);
        policy.setModelConfigId("model-1");
        policy.setCustomPrompt("仅使用带版本号和发布日期的官方公告");
        HarnessMaintenanceResponse response = new HarnessMaintenanceResponse();
        when(runDao.selectById("run-1")).thenReturn(run);
        when(policyDao.selectById("policy-1")).thenReturn(policy);
        when(snapshots.build(policy)).thenReturn(Map.of("name", "游戏专用"));
        when(resolver.resolve("model-1")).thenReturn(new HarnessModelConfig(
                "managed-key", "https://api.deepseek.com", "deepseek-chat"));
        when(gateway.run(any())).thenReturn(response);
        MaintenanceService service = new MaintenanceService(policyDao, runDao, snapshots, proposals, resolver,
                gateway, new ObjectMapper(), mock(AsyncTaskExecutor.class), mock(MaintenanceRunEventService.class),
                mock(MaintenanceConversationService.class));

        service.execute("run-1");

        ArgumentCaptor<HarnessMaintenanceRequest> request = ArgumentCaptor.forClass(HarnessMaintenanceRequest.class);
        verify(gateway).run(request.capture());
        assertEquals("仅使用带版本号和发布日期的官方公告", request.getValue().getCustomPrompt());
        assertEquals("只检查 18.1 当前赛季", request.getValue().getUserInstruction());
        assertEquals("conversation-1", request.getValue().getConversationId());
    }

    private MaintenancePolicyRequest request(String mode) {
        MaintenancePolicyRequest request = new MaintenancePolicyRequest();
        request.setMode(mode);
        request.setScheduleMinutes(360);
        request.setMaxProposals(8);
        request.setMaxMonthlyRuns(60);
        return request;
    }

    private MaintenanceService service(MaintenancePolicyDao policyDao) {
        return new MaintenanceService(policyDao, mock(MaintenanceRunDao.class),
                mock(MaintenanceSnapshotService.class), mock(MaintenanceProposalService.class),
                mock(MaintenanceModelResolver.class),
                mock(HarnessMaintenanceGateway.class), new ObjectMapper(),
                mock(AsyncTaskExecutor.class), mock(MaintenanceRunEventService.class),
                mock(MaintenanceConversationService.class));
    }
}

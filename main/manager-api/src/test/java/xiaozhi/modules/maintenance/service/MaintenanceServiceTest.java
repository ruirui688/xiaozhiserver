package xiaozhi.modules.maintenance.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.AsyncTaskExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.maintenance.dao.MaintenancePolicyDao;
import xiaozhi.modules.maintenance.dao.MaintenanceRunDao;
import xiaozhi.modules.maintenance.dto.MaintenancePolicyRequest;
import xiaozhi.modules.maintenance.entity.MaintenancePolicyEntity;

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
        request.setEnabled(true);

        service.savePolicy("KNOWLEDGE", "kb-1", "ws-1", request);

        ArgumentCaptor<MaintenancePolicyEntity> captor = ArgumentCaptor.forClass(MaintenancePolicyEntity.class);
        verify(dao).insert(captor.capture());
        assertEquals("kb-1", captor.getValue().getResourceId());
        assertEquals("KNOWLEDGE", captor.getValue().getResourceType());
        assertEquals(4, captor.getValue().getMaxProposals());
        assertEquals(12, captor.getValue().getMaxMonthlyRuns());
        assertEquals("deepseek-model", captor.getValue().getModelConfigId());
        assertEquals(1, captor.getValue().getEnabled());
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
                mock(AsyncTaskExecutor.class));
    }
}

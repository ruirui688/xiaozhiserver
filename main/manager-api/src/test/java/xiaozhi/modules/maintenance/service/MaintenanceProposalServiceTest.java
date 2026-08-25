package xiaozhi.modules.maintenance.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import xiaozhi.modules.knowledge.source.service.KnowledgeSourceService;
import xiaozhi.modules.knowledge.source.dto.KnowledgeSourceRequest;
import xiaozhi.modules.maintenance.dao.MaintenanceProposalDao;
import xiaozhi.modules.maintenance.dto.HarnessMaintenanceResponse;
import xiaozhi.modules.maintenance.entity.MaintenancePolicyEntity;
import xiaozhi.modules.maintenance.entity.MaintenanceProposalEntity;
import xiaozhi.modules.maintenance.entity.MaintenanceRunEntity;
import xiaozhi.modules.memory.dao.MemoryFactDao;
import xiaozhi.modules.memory.entity.MemoryFactEntity;
import xiaozhi.modules.memory.service.MemoryService;

class MaintenanceProposalServiceTest {
    @Test
    void memoryOutputCanOnlyReferenceAnExistingActiveFact() {
        MaintenanceProposalDao proposalDao = mock(MaintenanceProposalDao.class);
        MemoryFactDao factDao = mock(MemoryFactDao.class);
        MemoryFactEntity fact = new MemoryFactEntity();
        fact.setMemoryId("memory-1");
        fact.setCanonicalText("旧内容");
        when(factDao.selectOne(any())).thenReturn(fact);
        when(proposalDao.selectCount(any())).thenReturn(0L);
        MaintenanceProposalService service = service(proposalDao, factDao);
        HarnessMaintenanceResponse response = new HarnessMaintenanceResponse();
        response.setProposals(List.of(Map.of(
                "actionType", "UPDATE", "targetId", "memory-1", "proposedText", "新内容",
                "rationale", "现有事实需要修正", "evidence", List.of("memory-1 当前文本"))));

        int accepted = service.persist(policy("MEMORY", "REVIEW"), run(), response);

        assertEquals(1, accepted);
        ArgumentCaptor<MaintenanceProposalEntity> captor = ArgumentCaptor.forClass(MaintenanceProposalEntity.class);
        verify(proposalDao).insert(captor.capture());
        assertEquals("PENDING", captor.getValue().getStatus());
        assertEquals("memory-1", captor.getValue().getTargetId());
        assertEquals("{\"canonicalText\":\"新内容\"}", captor.getValue().getPayloadJson());
    }

    @Test
    void unsupportedMemoryAddProposalIsDiscarded() {
        MaintenanceProposalDao proposalDao = mock(MaintenanceProposalDao.class);
        MaintenanceProposalService service = service(proposalDao, mock(MemoryFactDao.class));
        HarnessMaintenanceResponse response = new HarnessMaintenanceResponse();
        response.setProposals(List.of(Map.of("actionType", "ADD_SOURCE", "sourceUri", "https://example.com",
                "rationale", "not allowed")));

        assertEquals(0, service.persist(policy("MEMORY", "REVIEW"), run(), response));
    }

    @Test
    void trustedKnowledgeSourceUsesPolicyCreatorAsBackgroundAuditActor() {
        MaintenanceProposalDao proposalDao = mock(MaintenanceProposalDao.class);
        KnowledgeSourceService sourceService = mock(KnowledgeSourceService.class);
        MaintenanceResourceAccessService accessService = mock(MaintenanceResourceAccessService.class);
        when(sourceService.list("resource-1")).thenReturn(List.of());
        when(proposalDao.selectCount(any())).thenReturn(0L);
        when(accessService.resolveWorkspace("KNOWLEDGE", "resource-1")).thenReturn("workspace-1");
        MaintenanceProposalService service = new MaintenanceProposalService(proposalDao, sourceService,
                mock(MemoryFactDao.class), mock(MemoryService.class), accessService, new ObjectMapper());
        MaintenancePolicyEntity policy = policy("KNOWLEDGE", "DISCOVERY_AUTO");
        policy.setCreator(42L);
        policy.setTrustedDomainsJson("[\"example.com\"]");
        HarnessMaintenanceResponse response = new HarnessMaintenanceResponse();
        response.setProposals(List.of(Map.of("actionType", "ADD_SOURCE",
                "sourceUri", "https://example.com/version", "title", "版本公告",
                "rationale", "发现可信来源")));

        assertEquals(1, service.persist(policy, run(), response));
        verify(sourceService).createManaged(org.mockito.ArgumentMatchers.eq("resource-1"),
                any(KnowledgeSourceRequest.class), org.mockito.ArgumentMatchers.eq(42L));
    }

    private MaintenanceProposalService service(MaintenanceProposalDao proposalDao, MemoryFactDao factDao) {
        return new MaintenanceProposalService(proposalDao, mock(KnowledgeSourceService.class), factDao,
                mock(MemoryService.class), mock(MaintenanceResourceAccessService.class), new ObjectMapper());
    }

    private MaintenancePolicyEntity policy(String type, String mode) {
        MaintenancePolicyEntity policy = new MaintenancePolicyEntity();
        policy.setId("policy-1");
        policy.setWorkspaceId("workspace-1");
        policy.setResourceType(type);
        policy.setResourceId("resource-1");
        policy.setMode(mode);
        policy.setMaxProposals(8);
        policy.setTrustedDomainsJson("[]");
        return policy;
    }

    private MaintenanceRunEntity run() {
        MaintenanceRunEntity run = new MaintenanceRunEntity();
        run.setId("run-1");
        return run;
    }
}

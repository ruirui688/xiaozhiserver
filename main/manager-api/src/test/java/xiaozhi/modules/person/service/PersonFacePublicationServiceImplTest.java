package xiaozhi.modules.person.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.person.dao.AgentFaceReleaseDao;
import xiaozhi.modules.person.dao.PersonAgentBindingDao;
import xiaozhi.modules.person.entity.AgentFaceReleaseEntity;
import xiaozhi.modules.person.entity.PersonAgentBindingEntity;
import xiaozhi.modules.person.entity.PersonProfileEntity;
import xiaozhi.modules.person.runtime.AgentFaceReleaseEvent;
import xiaozhi.modules.person.service.impl.PersonFacePublicationServiceImpl;

class PersonFacePublicationServiceImplTest {
    @Test
    void publishSnapshotsDraftAndAdvancesAgentRelease() {
        PersonAgentBindingDao bindingDao = mock(PersonAgentBindingDao.class);
        AgentFaceReleaseDao releaseDao = mock(AgentFaceReleaseDao.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        PersonFacePublicationServiceImpl service = new PersonFacePublicationServiceImpl(
                bindingDao, releaseDao, mock(AgentDao.class), mock(DeviceDao.class), publisher);

        PersonProfileEntity profile = new PersonProfileEntity();
        profile.setId("person-a");
        profile.setWorkspaceId("workspace-a");
        profile.setName("小明");
        profile.setGroupName("访客");
        profile.setDescription("画电路板的");
        profile.setStatus(1);
        profile.setCurrentFaceAssetId("asset-v2");
        profile.setRecognitionAction("SPEAK_TEXT");
        profile.setGreetingText("  欢迎光临  ");

        PersonAgentBindingEntity binding = new PersonAgentBindingEntity();
        binding.setId("binding-a");
        binding.setWorkspaceId("workspace-a");
        binding.setPersonId("person-a");
        binding.setAgentId("agent-a");
        binding.setDesiredActive(1);
        binding.setPublishedActive(0);
        when(bindingDao.selectList(any())).thenReturn(List.of(binding));
        when(releaseDao.selectOne(any())).thenReturn(null);

        assertEquals(List.of("agent-a"), service.publish(profile));

        assertEquals(1, binding.getPublishedActive());
        assertEquals("asset-v2", binding.getPublishedFaceAssetId());
        assertEquals("小明", binding.getPublishedPersonName());
        assertEquals("画电路板的", binding.getPublishedDescription());
        assertEquals("SPEAK_TEXT", binding.getPublishedRecognitionAction());
        assertEquals("欢迎光临", binding.getPublishedGreetingText());
        verify(bindingDao).updateById(binding);

        ArgumentCaptor<AgentFaceReleaseEntity> releaseCaptor = ArgumentCaptor.forClass(AgentFaceReleaseEntity.class);
        verify(releaseDao).insert(releaseCaptor.capture());
        assertEquals("workspace-a", releaseCaptor.getValue().getWorkspaceId());
        assertEquals("agent-a", releaseCaptor.getValue().getAgentId());
        assertEquals(1L, releaseCaptor.getValue().getReleaseVersion());

        ArgumentCaptor<AgentFaceReleaseEvent> eventCaptor = ArgumentCaptor.forClass(AgentFaceReleaseEvent.class);
        verify(publisher).publishEvent(eventCaptor.capture());
        assertEquals(List.of("agent-a"), eventCaptor.getValue().agentIds());
    }
}

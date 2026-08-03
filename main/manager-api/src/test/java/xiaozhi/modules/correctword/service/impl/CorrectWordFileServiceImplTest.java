package xiaozhi.modules.correctword.service.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.context.ApplicationEventPublisher;

import xiaozhi.modules.agent.dao.AgentCorrectWordMappingDao;
import xiaozhi.modules.agent.runtime.AgentRuntimeConfigRefreshEvent;
import xiaozhi.modules.correctword.dao.CorrectWordFileDao;
import xiaozhi.modules.correctword.dao.CorrectWordItemDao;
import xiaozhi.modules.correctword.dto.CorrectWordFileCreateDTO;
import xiaozhi.modules.correctword.entity.CorrectWordFileEntity;
import xiaozhi.modules.security.user.SecurityUser;

class CorrectWordFileServiceImplTest {
    private CorrectWordFileDao fileDao;
    private CorrectWordItemDao itemDao;
    private AgentCorrectWordMappingDao mappingDao;
    private ApplicationEventPublisher eventPublisher;
    private CorrectWordFileServiceImpl service;

    @BeforeEach
    void setUp() {
        fileDao = mock(CorrectWordFileDao.class);
        itemDao = mock(CorrectWordItemDao.class);
        mappingDao = mock(AgentCorrectWordMappingDao.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new CorrectWordFileServiceImpl(
                fileDao, itemDao, mappingDao, eventPublisher);
    }

    @Test
    void rejectsDeletingAFileNotOwnedByCurrentUser() {
        try (MockedStatic<SecurityUser> security = mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUserId).thenReturn(7L);
            when(fileDao.selectOne(any())).thenReturn(null);

            service.deleteFile("foreign-file");
        }

        verify(mappingDao, never()).deleteByFileId(any());
        verify(fileDao, never()).deleteById(any(java.io.Serializable.class));
    }

    @Test
    void updatingAnOwnedFilePublishesRefreshForReferencingAgents() {
        CorrectWordFileEntity ownedFile = new CorrectWordFileEntity();
        ownedFile.setId("file-a");
        ownedFile.setCreator(7L);
        CorrectWordFileCreateDTO dto = new CorrectWordFileCreateDTO();
        dto.setFileName("names.txt");
        dto.setContent(List.of("小治|小智"));

        try (MockedStatic<SecurityUser> security = mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUserId).thenReturn(7L);
            when(fileDao.selectOne(any())).thenReturn(ownedFile);
            when(mappingDao.selectAgentIdsByFileId("file-a"))
                    .thenReturn(List.of("agent-a"));

            service.updateFile("file-a", dto);
        }

        verify(fileDao).updateById(ownedFile);
        verify(eventPublisher).publishEvent(
                new AgentRuntimeConfigRefreshEvent(
                        List.of("agent-a"), "correct_word_file_updated"));
    }
}

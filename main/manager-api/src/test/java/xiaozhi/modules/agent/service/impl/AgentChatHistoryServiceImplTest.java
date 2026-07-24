package xiaozhi.modules.agent.service.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import com.baomidou.mybatisplus.core.conditions.Wrapper;

import xiaozhi.modules.agent.dao.AiAgentChatHistoryDao;
import xiaozhi.modules.agent.dao.AgentChatTitleDao;

class AgentChatHistoryServiceImplTest {

    @Test
    void clearsChatRowsBeforeSchedulingAudioCleanup() {
        AiAgentChatHistoryDao historyDao = mock(AiAgentChatHistoryDao.class);
        AgentChatTitleDao titleDao = mock(AgentChatTitleDao.class);
        AgentChatAudioCleanupService cleanupService = mock(AgentChatAudioCleanupService.class);
        AgentChatHistoryServiceImpl service = new AgentChatHistoryServiceImpl(titleDao, cleanupService);
        ReflectionTestUtils.setField(service, "baseMapper", historyDao);
        when(historyDao.selectObjs(any(Wrapper.class))).thenReturn(List.of("session-1"));
        when(historyDao.getAudioIdsByAgentId("agent-1")).thenReturn(List.of("audio-1", "audio-2"));

        service.deleteByAgentId("agent-1", true, true);

        InOrder order = inOrder(historyDao, titleDao, cleanupService);
        order.verify(historyDao).getAudioIdsByAgentId("agent-1");
        order.verify(historyDao).deleteHistoryByAgentId("agent-1");
        order.verify(titleDao).delete(any(Wrapper.class));
        order.verify(cleanupService).scheduleAfterCommit(List.of("audio-1", "audio-2"));
    }
}

package xiaozhi.modules.agent.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import xiaozhi.modules.agent.dao.AiAgentChatHistoryDao;

class AgentChatAudioCleanupServiceTest {

    @Test
    void cleansDistinctAudioIdsInSmallBatches() {
        AiAgentChatHistoryDao dao = mock(AiAgentChatHistoryDao.class);
        when(dao.deleteUnreferencedAudioByIds(anyList())).thenAnswer(invocation -> invocation.<List<?>>getArgument(0).size());
        Executor directExecutor = Runnable::run;
        AgentChatAudioCleanupService service = new AgentChatAudioCleanupService(dao, directExecutor);

        List<String> audioIds = new ArrayList<>();
        for (int index = 0; index < 121; index++) {
            audioIds.add("audio-" + index);
        }
        audioIds.add("audio-0");
        audioIds.add("");
        audioIds.add(null);

        service.scheduleAfterCommit(audioIds);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> batches = ArgumentCaptor.forClass(List.class);
        verify(dao, org.mockito.Mockito.times(3)).deleteUnreferencedAudioByIds(batches.capture());
        assertEquals(List.of(50, 50, 21), batches.getAllValues().stream().map(List::size).toList());
    }

    @Test
    void doesNotSubmitCleanupUntilTransactionCommits() {
        AiAgentChatHistoryDao dao = mock(AiAgentChatHistoryDao.class);
        Executor executor = mock(Executor.class);
        AgentChatAudioCleanupService service = new AgentChatAudioCleanupService(dao, executor);

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.scheduleAfterCommit(List.of("audio-1"));
            verify(executor, never()).execute(org.mockito.ArgumentMatchers.any(Runnable.class));

            List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1, synchronizations.size());
            synchronizations.get(0).afterCommit();

            verify(executor).execute(org.mockito.ArgumentMatchers.any(Runnable.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}

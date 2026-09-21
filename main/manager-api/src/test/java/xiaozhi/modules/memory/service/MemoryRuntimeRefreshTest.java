package xiaozhi.modules.memory.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalApplicationListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import xiaozhi.modules.agent.runtime.AgentRuntimeConfigRefreshEvent;

class MemoryRuntimeRefreshTest {
    @AfterEach
    void cleanup() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void notifiesOncePerAgentOnlyAfterCommitAndNeverAfterRollback() {
        List<AgentRuntimeConfigRefreshEvent> delivered = new ArrayList<>();
        try (var context = new GenericApplicationContext()) {
            context.addApplicationListener(TransactionalApplicationListener.forPayload(
                    TransactionPhase.AFTER_COMMIT, (AgentRuntimeConfigRefreshEvent event) -> delivered.add(event)));
            context.refresh();
            var refresh = new MemoryRuntimeRefresh(context);
            TransactionSynchronizationManager.setActualTransactionActive(true);
            TransactionSynchronizationManager.initSynchronization();
            refresh.changed("agent-a");
            refresh.changed("agent-a");
            refresh.changed("agent-b");
            assertTrue(delivered.isEmpty());
            TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_COMMITTED);
            assertEquals(2, delivered.size());
            assertTrue(delivered.stream().allMatch(event -> event.reason().equals("memory_changed")));
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.initSynchronization();
            refresh.changed("agent-a");
            TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            assertEquals(2, delivered.size());
        }
    }
}

package xiaozhi.modules.memory.service;

import java.util.HashSet;
import java.util.Set;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.RequiredArgsConstructor;
import xiaozhi.modules.agent.runtime.AgentRuntimeConfigRefreshEvent;

/** Publish once per agent/transaction; the existing listener delivers only AFTER_COMMIT. */
@Component
@RequiredArgsConstructor
public class MemoryRuntimeRefresh {
    private final ApplicationEventPublisher publisher;
    public void changed(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            Pending pending = TransactionSynchronizationManager.getSynchronizations().stream()
                    .filter(item -> item instanceof Pending)
                    .map(item -> (Pending) item).findFirst().orElse(null);
            if (pending == null) {
                pending = new Pending();
                TransactionSynchronizationManager.registerSynchronization(pending);
            }
            if (!pending.agents.add(agentId)) {
                return;
            }
        }
        publisher.publishEvent(AgentRuntimeConfigRefreshEvent.forAgent(agentId, "memory_changed"));
    }

    private static final class Pending implements TransactionSynchronization {
        private final Set<String> agents = new HashSet<>();
    }
}

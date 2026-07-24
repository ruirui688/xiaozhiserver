package xiaozhi.modules.agent.service.impl;

import java.util.List;
import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.extern.slf4j.Slf4j;
import xiaozhi.modules.agent.dao.AiAgentChatHistoryDao;

@Service
@Slf4j
public class AgentChatAudioCleanupService {
    static final int DELETE_BATCH_SIZE = 50;

    private final AiAgentChatHistoryDao chatHistoryDao;
    private final Executor cleanupExecutor;

    public AgentChatAudioCleanupService(
            AiAgentChatHistoryDao chatHistoryDao,
            @Qualifier("agentChatAudioCleanupExecutor") Executor cleanupExecutor) {
        this.chatHistoryDao = chatHistoryDao;
        this.cleanupExecutor = cleanupExecutor;
    }

    /**
     * 聊天记录事务提交后再清理大体积音频，避免 Blob 删除阻塞用户请求。
     */
    public void scheduleAfterCommit(List<String> audioIds) {
        if (audioIds == null || audioIds.isEmpty()) {
            return;
        }
        List<String> cleanupIds = audioIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (cleanupIds.isEmpty()) {
            return;
        }

        Runnable submitTask = () -> submit(cleanupIds);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submitTask.run();
                }
            });
            return;
        }
        submitTask.run();
    }

    private void submit(List<String> audioIds) {
        try {
            cleanupExecutor.execute(() -> cleanup(audioIds));
        } catch (RuntimeException error) {
            log.warn("聊天音频后台清理任务提交失败: count={}, errorType={}",
                    audioIds.size(), error.getClass().getSimpleName());
        }
    }

    void cleanup(List<String> audioIds) {
        int deleted = 0;
        int failedBatches = 0;
        for (int start = 0; start < audioIds.size(); start += DELETE_BATCH_SIZE) {
            int end = Math.min(start + DELETE_BATCH_SIZE, audioIds.size());
            List<String> batch = audioIds.subList(start, end);
            try {
                deleted += chatHistoryDao.deleteUnreferencedAudioByIds(batch);
            } catch (RuntimeException error) {
                failedBatches++;
                log.warn("聊天音频后台分批清理失败: batchSize={}, errorType={}",
                        batch.size(), error.getClass().getSimpleName());
            }
        }
        log.info("聊天音频后台清理完成: requested={}, deleted={}, failedBatches={}",
                audioIds.size(), deleted, failedBatches);
    }
}

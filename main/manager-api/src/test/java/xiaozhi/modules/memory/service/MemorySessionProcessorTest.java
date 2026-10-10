package xiaozhi.modules.memory.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import cn.hutool.json.JSONObject;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.llm.service.LLMService;
import xiaozhi.modules.memory.dao.MemoryCandidateDao;
import xiaozhi.modules.memory.dao.MemoryRevisionDao;
import xiaozhi.modules.memory.dto.MemorySessionMessageDTO;
import xiaozhi.modules.memory.entity.MemoryRevisionEntity;
import xiaozhi.modules.memory.service.impl.maintenance.MemoryMaintenanceOutputParser;
import xiaozhi.modules.memory.service.impl.maintenance.MemorySessionProcessor;
import xiaozhi.modules.memory.service.impl.maintenance.MemorySpeakerConversationBuilder;
import org.springframework.transaction.support.TransactionTemplate;

class MemorySessionProcessorTest {
    @Test
    void replayAfterFactCommitReturnsOriginalResultWithoutCallingModelAgain() {
        var revisions = mock(MemoryRevisionDao.class);
        var llm = mock(LLMService.class);
        when(revisions.selectCount(any())).thenReturn(1L);
        var revision = new MemoryRevisionEntity();
        revision.setAfterPayload("{\"factsChanged\":3}");
        when(revisions.selectOne(any())).thenReturn(revision);
        var processor = new MemorySessionProcessor(null, null, mock(AgentDao.class), mock(MemoryCandidateDao.class),
                revisions, llm, new MemoryMaintenanceOutputParser(), new MemorySpeakerConversationBuilder(),
                mock(TransactionTemplate.class), mock(MemoryRuntimeRefresh.class));
        var agent = new AgentEntity(); agent.setId("agent");
        var message = new MemorySessionMessageDTO(); message.setRole("user"); message.setContent("事实");
        assertEquals(3, processor.process(agent, "segment", List.of(message), new JSONObject(),
                () -> { throw new AssertionError("must not read model context"); },
                action -> { throw new AssertionError("must not apply twice"); }, action -> null));
        verifyNoInteractions(llm);
    }
}

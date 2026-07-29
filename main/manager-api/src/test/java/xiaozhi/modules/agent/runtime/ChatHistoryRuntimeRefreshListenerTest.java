package xiaozhi.modules.agent.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import xiaozhi.common.constant.Constant;
import xiaozhi.modules.sys.service.SysParamsService;

class ChatHistoryRuntimeRefreshListenerTest {
    @Test
    void refreshFailureDoesNotTurnCommittedAgentUpdateIntoAnError() {
        SysParamsService sysParamsService = mock(SysParamsService.class);
        Environment environment = mock(Environment.class);
        when(sysParamsService.getValue(Constant.SERVER_SECRET, true))
                .thenThrow(new IllegalStateException("configuration unavailable"));
        ChatHistoryRuntimeRefreshListener listener =
                new ChatHistoryRuntimeRefreshListener(sysParamsService, environment);

        assertDoesNotThrow(() -> listener.refreshOnlineConnections(
                new ChatHistoryRuntimeRefreshEvent("agent-a", 2)));
    }
}

package xiaozhi.modules.agent.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import xiaozhi.common.constant.Constant;
import xiaozhi.modules.sys.service.SysParamsService;

class AgentRuntimeConfigRefreshListenerTest {
    @Test
    void refreshFailureDoesNotTurnCommittedConfigUpdateIntoAnError() {
        SysParamsService sysParamsService = mock(SysParamsService.class);
        Environment environment = mock(Environment.class);
        when(sysParamsService.getValue(Constant.SERVER_SECRET, true))
                .thenThrow(new IllegalStateException("configuration unavailable"));
        AgentRuntimeConfigRefreshListener listener =
                new AgentRuntimeConfigRefreshListener(sysParamsService, environment);

        assertDoesNotThrow(() -> listener.refreshOnlineConnections(
                AgentRuntimeConfigRefreshEvent.forAgent("agent-a", "agent_updated")));
    }
}

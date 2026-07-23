package xiaozhi.modules.agent.voiceprint;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import xiaozhi.common.constant.Constant;
import xiaozhi.modules.sys.service.SysParamsService;

class VoiceprintRuntimeRefreshListenerTest {
    @Test
    void refreshFailureDoesNotTurnCommittedRegistrationIntoAnError() {
        SysParamsService sysParamsService = mock(SysParamsService.class);
        Environment environment = mock(Environment.class);
        when(sysParamsService.getValue(Constant.SERVER_SECRET, true))
                .thenThrow(new IllegalStateException("configuration unavailable"));
        VoiceprintRuntimeRefreshListener listener =
                new VoiceprintRuntimeRefreshListener(sysParamsService, environment);

        assertDoesNotThrow(() -> listener.refreshOnlineDevice(
                new VoiceprintRegisteredEvent("agent-a", "device-a", "voice-a")));
    }
}

package xiaozhi.modules.model.service.impl.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import xiaozhi.common.constant.Constant;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.sys.service.SysParamsService;

class BenchmarkRequestAuthTest {

    @Test
    void appliesBearerServerSecretWithoutExposingItElsewhere() {
        SysParamsService sysParamsService = mock(SysParamsService.class);
        when(sysParamsService.getValue(Constant.SERVER_SECRET, true)).thenReturn("server-test-secret");
        BenchmarkRequestAuth auth = new BenchmarkRequestAuth(sysParamsService);
        HttpHeaders headers = new HttpHeaders();

        auth.apply(headers);

        assertEquals("Bearer server-test-secret", headers.getFirst(HttpHeaders.AUTHORIZATION));
    }

    @Test
    void rejectsBenchmarkRequestWhenServerSecretIsMissing() {
        SysParamsService sysParamsService = mock(SysParamsService.class);
        when(sysParamsService.getValue(Constant.SERVER_SECRET, true)).thenReturn(" ");
        BenchmarkRequestAuth auth = new BenchmarkRequestAuth(sysParamsService);

        RenException error = assertThrows(RenException.class, () -> auth.apply(new HttpHeaders()));

        assertTrue(error.getMessage().contains("server.secret 未配置"));
        assertFalse(error.getMessage().contains("Bearer"));
    }
}

package xiaozhi.modules.memory.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.baomidou.mybatisplus.core.conditions.Wrapper;

import xiaozhi.common.constant.Constant;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.memory.dao.MemoryFactDao;
import xiaozhi.modules.memory.entity.MemoryFactEntity;
import xiaozhi.modules.sys.service.SysParamsService;

class MemorySettingsServiceImplTest {

    private final SysParamsService sysParamsService = mock(SysParamsService.class);
    private final MemoryFactDao factDao = mock(MemoryFactDao.class);
    private final MemorySettingsServiceImpl service = new MemorySettingsServiceImpl(sysParamsService, factDao);

    @Test
    void activeContextMaxCharsFallsBackToDefaultWhenParamMissingOrInvalid() {
        when(sysParamsService.getValue(Constant.MEMORY_ACTIVE_CONTEXT_MAX_CHARS, true)).thenReturn("null");
        assertEquals(1000, service.activeContextMaxChars());

        when(sysParamsService.getValue(Constant.MEMORY_ACTIVE_CONTEXT_MAX_CHARS, true)).thenReturn("bad");
        assertEquals(1000, service.activeContextMaxChars());
    }

    @Test
    void validateChangeRejectsLimitBelowExistingActiveMemoryUsage() {
        when(factDao.selectList(any(Wrapper.class))).thenReturn(List.of(
                fact("agent-a", "旧".repeat(700)),
                fact("agent-a", "记".repeat(350)),
                fact("agent-b", "短记忆")));

        RenException error = assertThrows(RenException.class,
                () -> service.validateActiveContextMaxCharsChange(
                        Constant.MEMORY_ACTIVE_CONTEXT_MAX_CHARS, "1000"));

        assertTrue(error.getMsg().contains("1050"));
        assertTrue(error.getMsg().contains("记忆审查/蒸馏"));
    }

    private MemoryFactEntity fact(String agentId, String text) {
        MemoryFactEntity fact = new MemoryFactEntity();
        fact.setAgentId(agentId);
        fact.setCanonicalText(text);
        return fact;
    }
}

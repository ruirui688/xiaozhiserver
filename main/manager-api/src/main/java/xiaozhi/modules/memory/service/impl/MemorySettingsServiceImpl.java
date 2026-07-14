package xiaozhi.modules.memory.service.impl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xiaozhi.common.constant.Constant;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.memory.dao.MemoryFactDao;
import xiaozhi.modules.memory.entity.MemoryFactEntity;
import xiaozhi.modules.memory.service.MemorySettingsService;
import xiaozhi.modules.memory.utils.MemoryHelper;
import xiaozhi.modules.sys.service.SysParamsService;

@Service
@AllArgsConstructor
@Slf4j
public class MemorySettingsServiceImpl implements MemorySettingsService {

    private final SysParamsService sysParamsService;
    private final MemoryFactDao factDao;

    @Override
    public int activeContextMaxChars() {
        String value = sysParamsService.getValue(Constant.MEMORY_ACTIVE_CONTEXT_MAX_CHARS, true);
        if (StringUtils.isBlank(value) || "null".equalsIgnoreCase(value.trim())) {
            return DEFAULT_ACTIVE_CONTEXT_MAX_CHARS;
        }
        try {
            return parseAndValidateLimit(value);
        } catch (RenException e) {
            log.warn("记忆容量参数无效，使用默认值: paramCode={}, value={}, error={}",
                    Constant.MEMORY_ACTIVE_CONTEXT_MAX_CHARS, value, e.getMsg());
            return DEFAULT_ACTIVE_CONTEXT_MAX_CHARS;
        }
    }

    @Override
    public void validateActiveContextMaxCharsChange(String paramCode, String paramValue) {
        if (!Constant.MEMORY_ACTIVE_CONTEXT_MAX_CHARS.equals(paramCode)) {
            return;
        }
        int newLimit = parseAndValidateLimit(paramValue);
        MemoryUsage usage = findMaxActiveMemoryUsage();
        if (usage.chars > newLimit) {
            throw new RenException("已有智能体的生效记忆总量为 " + usage.chars
                    + " 字，超过新的上限 " + newLimit
                    + " 字。请先在记忆管理中执行记忆审查/蒸馏，归档或合并低价值记忆后再降低上限。");
        }
    }

    private int parseAndValidateLimit(String value) {
        int limit;
        try {
            limit = Integer.parseInt(StringUtils.trimToEmpty(value));
        } catch (NumberFormatException e) {
            throw new RenException("记忆容量上限必须是整数");
        }
        if (limit < MIN_ACTIVE_CONTEXT_MAX_CHARS || limit > MAX_ACTIVE_CONTEXT_MAX_CHARS) {
            throw new RenException("记忆容量上限必须在 " + MIN_ACTIVE_CONTEXT_MAX_CHARS
                    + " 到 " + MAX_ACTIVE_CONTEXT_MAX_CHARS + " 字之间");
        }
        return limit;
    }

    private MemoryUsage findMaxActiveMemoryUsage() {
        List<MemoryFactEntity> activeFacts = factDao.selectList(new QueryWrapper<MemoryFactEntity>()
                .eq("status", MemoryHelper.STATUS_ACTIVE));
        Map<String, Integer> usageByAgent = new HashMap<>();
        for (MemoryFactEntity fact : activeFacts) {
            if (fact == null || StringUtils.isBlank(fact.getAgentId())) {
                continue;
            }
            usageByAgent.merge(fact.getAgentId(),
                    MemoryHelper.characterCount(fact.getCanonicalText()), Integer::sum);
        }
        String maxAgentId = "";
        int maxChars = 0;
        for (Map.Entry<String, Integer> entry : usageByAgent.entrySet()) {
            if (entry.getValue() > maxChars) {
                maxAgentId = entry.getKey();
                maxChars = entry.getValue();
            }
        }
        return new MemoryUsage(maxAgentId, maxChars);
    }

    private record MemoryUsage(String agentId, int chars) {
    }
}

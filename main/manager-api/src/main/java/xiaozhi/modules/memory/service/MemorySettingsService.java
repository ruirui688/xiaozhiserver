package xiaozhi.modules.memory.service;

import xiaozhi.modules.memory.utils.MemoryHelper;

public interface MemorySettingsService {

    int DEFAULT_ACTIVE_CONTEXT_MAX_CHARS = MemoryHelper.DEFAULT_ACTIVE_CONTEXT_MAX_CHARS;
    int MIN_ACTIVE_CONTEXT_MAX_CHARS = 100;
    int MAX_ACTIVE_CONTEXT_MAX_CHARS = 20000;

    int activeContextMaxChars();

    void validateActiveContextMaxCharsChange(String paramCode, String paramValue);
}

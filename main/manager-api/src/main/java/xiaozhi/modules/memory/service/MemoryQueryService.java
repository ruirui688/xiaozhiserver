package xiaozhi.modules.memory.service;

import xiaozhi.modules.memory.dto.MemoryQueryDTO;
import xiaozhi.modules.memory.vo.MemoryQueryVO;

/**
 * 长期记忆查询服务。
 */
public interface MemoryQueryService {

    /**
     * 查询长期记忆，返回召回结果、画像、工具行为、忘记名单和上下文文本。
     */
    MemoryQueryVO query(MemoryQueryDTO dto);
}

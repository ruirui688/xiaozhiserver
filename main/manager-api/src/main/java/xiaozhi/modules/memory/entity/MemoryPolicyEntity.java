package xiaozhi.modules.memory.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("ai_memory_policy")
public class MemoryPolicyEntity {
    @TableId
    private String agentId;
    private String policyJson;
}

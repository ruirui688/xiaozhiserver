package xiaozhi.modules.memory.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ai_memory_profile")
public class MemoryProfileEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String profileId;
    private String agentId;
    private Long userId;
    private String workspaceId;
    private String scopeType;
    private String scopeId;
    @TableField("profile_json")
    private String profileJson;
    private String profileSummary;
    private String profileVersion;
    private Date updatedFromFactTs;
    private Date createdAt;
    private Date updatedAt;
}

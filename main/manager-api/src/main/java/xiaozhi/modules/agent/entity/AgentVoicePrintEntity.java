package xiaozhi.modules.agent.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 智能体声纹表
 *
 * @author zjy
 */
@TableName(value = "ai_agent_voice_print")
@Data
public class AgentVoicePrintEntity {
    /**
     * 主键id
     */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    /**
     * 关联的智能体id
     */
    private String agentId;
    /**
     * 声纹资源所属工作空间；绑定只能发生在同一工作空间内。
     */
    private String workspaceId;
    /**
     * 关联的人物档案。一个人物最多关联一个逻辑声纹，空值表示尚未归入人物档案。
     */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String personId;
    /**
     * 关联的音频id
     */
    private String audioId;
    /**
     * 远程 voiceprint-api 的 user_id。历史数据统一为 default，新数据使用 agentId。
     */
    private String remoteUserId;
    /**
     * 声纹来源的人姓名
     */
    private String sourceName;
    /**
     * 描述声纹来源的人
     */
    private String introduce;

    /**
     * 创建者
     */
    @TableField(fill = FieldFill.INSERT)
    private Long creator;
    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private Date createDate;

    /**
     * 更新者
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updater;
    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateDate;
}

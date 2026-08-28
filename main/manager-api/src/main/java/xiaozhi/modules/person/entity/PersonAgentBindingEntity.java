package xiaozhi.modules.person.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ai_person_agent_binding")
public class PersonAgentBindingEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String workspaceId;
    private String personId;
    private String agentId;
    private Integer desiredActive;
    private Integer publishedActive;
    private String publishedPersonName;
    private String publishedGroupName;
    private String publishedDescription;
    private String publishedFaceAssetId;
    private String publishedRecognitionAction;
    private String publishedGreetingText;
    @TableField(fill = FieldFill.INSERT)
    private Long creator;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updater;
    @TableField(fill = FieldFill.INSERT)
    private Date createDate;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateDate;
}

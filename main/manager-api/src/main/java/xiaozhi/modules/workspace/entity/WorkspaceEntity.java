package xiaozhi.modules.workspace.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("sys_workspace")
public class WorkspaceEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String name;
    private Long ownerUserId;
    private Integer defaultWorkspace;
    private Integer status;
    @TableField(fill = FieldFill.INSERT)
    private Long creator;
    @TableField(fill = FieldFill.INSERT)
    private Date createdAt;
    @TableField(fill = FieldFill.UPDATE)
    private Long updater;
    @TableField(fill = FieldFill.UPDATE)
    private Date updatedAt;
}

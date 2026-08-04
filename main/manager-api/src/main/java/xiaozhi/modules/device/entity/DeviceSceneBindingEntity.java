package xiaozhi.modules.device.entity;

import java.util.Date;
import java.util.List;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;

import lombok.Data;

@Data
@TableName(value = "ai_device_scene_binding", autoResultMap = true)
public class DeviceSceneBindingEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String workspaceId;
    private String deviceId;
    private String agentId;
    private String sceneName;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> switchAliases;
    private Integer enabled;
    private Integer sort;
    private String templateId;
    private Integer templateVersion;
    @TableField(fill = FieldFill.INSERT)
    private Long creator;
    @TableField(fill = FieldFill.INSERT)
    private Date createDate;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updater;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateDate;
}

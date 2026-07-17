package xiaozhi.modules.userplugin.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ai_user_plugin_config")
public class UserPluginConfigEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private String providerCode;
    private String configCiphertext;
    private Long creator;
    private Date createdAt;
    private Long updater;
    private Date updatedAt;
}

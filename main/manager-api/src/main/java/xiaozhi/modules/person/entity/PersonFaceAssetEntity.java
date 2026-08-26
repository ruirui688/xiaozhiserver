package xiaozhi.modules.person.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ai_person_face_asset")
public class PersonFaceAssetEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String workspaceId;
    private String personId;
    private Long assetVersion;
    private String filePath;
    private String contentType;
    private String sha256;
    private Long creator;
    private Date createDate;
}

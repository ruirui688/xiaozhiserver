package xiaozhi.modules.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@TableName("ai_model_config_secret")
@Schema(description = "模型密钥表")
public class ModelConfigSecretEntity {

    @Schema(description = "主键")
    private Long id;

    @Schema(description = "模型配置ID")
    private String modelId;

    @Schema(description = "密钥字段")
    private String secretKey;

    @Schema(description = "密钥值")
    private String secretValue;

    @Schema(description = "脱敏展示值")
    private String maskedValue;

    @Schema(description = "值类型")
    private String valueType;
}

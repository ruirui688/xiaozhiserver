package xiaozhi.modules.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@TableName("ai_model_config_param")
@Schema(description = "模型标准参数表")
public class ModelConfigParamEntity {

    @Schema(description = "主键")
    private Long id;

    @Schema(description = "模型配置ID")
    private String modelId;

    @Schema(description = "参数键")
    private String paramKey;

    @Schema(description = "参数值")
    private String paramValue;

    @Schema(description = "值类型(string/number/boolean/json)")
    private String valueType;

    @Schema(description = "参数分组(endpoint/generation/audio/provider/advanced)")
    private String paramGroup;
}

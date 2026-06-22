package xiaozhi.modules.model.dto;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "模型配置标准化预览")
public class ModelMigrationPreviewDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "配置ID")
    private String id;

    @Schema(description = "配置名称")
    private String modelName;

    @Schema(description = "模型类型")
    private String modelType;

    @Schema(description = "适配器编码")
    private String providerCode;

    @Schema(description = "匹配到的模板编码")
    private String templateCode;

    @Schema(description = "模板名称")
    private String templateName;

    @Schema(description = "迁移状态：AUTO/NEEDS_REVIEW/UNSUPPORTED")
    private String status;

    @Schema(description = "缺失字段")
    private List<String> missingFields = List.of();

    @Schema(description = "废弃或不建议继续展示的字段")
    private List<String> deprecatedFields = List.of();

    @Schema(description = "风险和处理建议")
    private List<String> notes = List.of();
}

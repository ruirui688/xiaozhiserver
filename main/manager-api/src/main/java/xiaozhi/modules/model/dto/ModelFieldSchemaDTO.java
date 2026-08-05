package xiaozhi.modules.model.dto;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "标准模型配置字段结构")
public class ModelFieldSchemaDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "字段名")
    private String key;

    @Schema(description = "展示名称")
    private String label;

    @Schema(description = "字段类型")
    private String type;

    @Schema(description = "输入控件类型")
    private String inputType;

    @Schema(description = "字段分组")
    private String group;

    @Schema(description = "运行时字段名")
    private String runtimeKey;

    @Schema(description = "是否高级字段")
    private Boolean advanced;

    @Schema(description = "显示条件表达式")
    private String visibleWhen;

    @Schema(description = "是否必填")
    private Boolean required;

    @Schema(description = "是否敏感字段")
    private Boolean secret;

    @Schema(description = "默认值")
    private Object defaultValue;

    @Schema(description = "可选项")
    private List<String> options = List.of();

    @Schema(description = "下拉框是否允许录入目录外的值")
    private Boolean allowCreate;

    @Schema(description = "帮助说明")
    private String helpText;

    @Schema(description = "排序")
    private Integer order;
}

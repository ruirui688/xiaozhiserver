package xiaozhi.modules.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@TableName("ai_model_template_field")
@Schema(description = "模型模板字段表")
public class ModelTemplateFieldEntity {

    @Schema(description = "主键")
    private Long id;

    @Schema(description = "模板ID")
    private String templateId;

    @Schema(description = "字段键")
    private String fieldKey;

    @Schema(description = "字段标签")
    private String fieldLabel;

    @Schema(description = "字段分组")
    private String fieldGroup;

    @Schema(description = "值类型")
    private String valueType;

    @Schema(description = "输入类型")
    private String inputType;

    @Schema(description = "运行时字段名")
    private String runtimeKey;

    @Schema(description = "是否高级字段")
    private Integer advanced;

    @Schema(description = "显示条件表达式")
    private String visibleWhen;

    @Schema(description = "是否必填")
    private Integer required;

    @Schema(description = "是否密钥")
    private Integer secret;

    @Schema(description = "默认值")
    private String defaultValue;

    @Schema(description = "选项JSON")
    private String optionsJson;

    @Schema(description = "提示文本")
    private String helpText;

    @Schema(description = "排序")
    private Integer sort;
}

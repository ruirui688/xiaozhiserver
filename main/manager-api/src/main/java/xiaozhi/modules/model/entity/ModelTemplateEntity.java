package xiaozhi.modules.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@TableName("ai_model_template")
@Schema(description = "模型模板表")
public class ModelTemplateEntity {

    @Schema(description = "主键")
    private String id;

    @Schema(description = "模板编码")
    private String templateCode;

    @Schema(description = "模型类型")
    private String modelType;

    @Schema(description = "适配器编码")
    private String adapterCode;

    @Schema(description = "厂商名称")
    private String vendorName;

    @Schema(description = "产品/API名称")
    private String productName;

    @Schema(description = "能力编码")
    private String capability;

    @Schema(description = "接入方式")
    private String accessMode;

    @Schema(description = "部署形态")
    private String deployment;

    @Schema(description = "协议编码")
    private String protocol;

    @Schema(description = "上游模型字段名")
    private String upstreamModelField;

    @Schema(description = "文档链接")
    private String docLink;

    @Schema(description = "是否启用")
    private Integer enabled;

    @Schema(description = "排序")
    private Integer sort;
}

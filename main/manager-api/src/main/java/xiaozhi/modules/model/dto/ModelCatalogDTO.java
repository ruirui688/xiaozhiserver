package xiaozhi.modules.model.dto;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "标准模型目录")
public class ModelCatalogDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "模板编码")
    private String templateCode;

    @Schema(description = "模型类型")
    private String modelType;

    @Schema(description = "运行时适配器编码")
    private String providerCode;

    @Schema(description = "适配器名称")
    private String adapterName;

    @Schema(description = "厂商")
    private String vendorName;

    @Schema(description = "产品/API")
    private String productName;

    @Schema(description = "能力编码")
    private String capability;

    @Schema(description = "能力展示名称")
    private String capabilityLabel;

    @Schema(description = "接入方式编码")
    private String accessMode;

    @Schema(description = "接入方式展示名称")
    private String accessModeLabel;

    @Schema(description = "部署形态编码")
    private String deployment;

    @Schema(description = "部署形态展示名称")
    private String deploymentLabel;

    @Schema(description = "协议展示名称")
    private String protocolLabel;

    @Schema(description = "上游模型/资源字段展示名称")
    private String upstreamResourceLabel;

    @Schema(description = "上游模型/资源字段名")
    private String upstreamModelField;

    @Schema(description = "凭据字段")
    private List<String> credentialFields = List.of();

    @Schema(description = "必填字段")
    private List<String> requiredFields = List.of();

    @Schema(description = "推荐关注字段")
    private List<String> recommendedFields = List.of();

    @Schema(description = "字段定义")
    private List<ModelFieldSchemaDTO> fieldSchemas = List.of();

    @Schema(description = "官方文档")
    private String docLink;

    @Schema(description = "配置提示")
    private List<String> setupHints = List.of();

    @Schema(description = "已知限制")
    private List<String> knownLimitations = List.of();

    @Schema(description = "是否隐藏")
    private Boolean hidden = false;

    @Schema(description = "是否废弃")
    private Boolean deprecated = false;

    @Schema(description = "适配状态：SUPPORTED/PARTIAL/NEEDS_ADAPTER/DEPRECATED")
    private String supportedStatus = "SUPPORTED";

    @Schema(description = "是否可以在模型配置中直接选择")
    private Boolean selectable = true;

    @Schema(description = "推荐场景")
    private String recommendedFor;

    @Schema(description = "不适用场景")
    private String avoidWhen;

    @Schema(description = "真实测试契约")
    private String testContract;

    @Schema(description = "目录来源最后核验日期")
    private String sourceCheckedAt;

    @Schema(description = "别名/旧配置名")
    private List<String> aliases = List.of();

    @Schema(description = "排序")
    private Integer sort;
}

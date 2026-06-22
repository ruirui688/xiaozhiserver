package xiaozhi.modules.model.dto;

import java.io.Serial;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "模型供应器/商")
public class ModelConfigBodyDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "模型ID,未填写将自动生成")
    private String id;

    @Schema(description = "模型编码(如AliLLM、DoubaoTTS)")
    private String modelCode;

    @Schema(description = "模型名称")
    private String modelName;

    @Schema(description = "运行时适配器编码")
    private String adapterCode;

    @Schema(description = "供应器/适配器编码")
    private String providerCode;

    @Schema(description = "模板编码")
    private String templateCode;

    @Schema(description = "厂商名称")
    private String vendorName;

    @Schema(description = "产品/API名称")
    private String productName;

    @Schema(description = "协议编码")
    private String protocol;

    @Schema(description = "上游真实模型或资源ID")
    private String upstreamModelId;

    @Schema(description = "接口端点地址")
    private String endpointUrl;

    @Schema(description = "标准普通参数")
    private Map<String, Object> params = new HashMap<>();

    @Schema(description = "标准密钥参数")
    private Map<String, Object> secrets = new HashMap<>();

    @Schema(description = "是否默认配置(0否 1是)")
    private Integer isDefault;

    @Schema(description = "是否启用")
    private Integer isEnabled;

    @Schema(description = "测试状态(0未测试 1通过 2失败)")
    private Integer testStatus;

    @Schema(description = "官方文档链接")
    private String docLink;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "排序")
    private Integer sort;
}

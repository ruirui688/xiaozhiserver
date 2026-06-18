package xiaozhi.modules.model.dto;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;
import java.util.List;

import cn.hutool.json.JSONObject;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "模型供应器/商")
public class ModelConfigDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    private String id;

    @Schema(description = "模型类型(Memory/ASR/VAD/LLM/TTS)")
    private String modelType;

    @Schema(description = "模型编码(如AliLLM、DoubaoTTS)")
    private String modelCode;

    @Schema(description = "模型名称")
    private String modelName;

    @Schema(description = "供应器/适配器编码，来源于configJson.type")
    private String providerCode = "";

    @Schema(description = "供应器/适配器名称，来源于ai_model_provider.name")
    private String providerName = "";

    @Schema(description = "接口适配器展示名称")
    private String adapterLabel = "";

    @Schema(description = "真实上游模型或资源ID")
    private String upstreamModelId = "";

    @Schema(description = "厂商平台展示名称")
    private String platformName = "";

    @Schema(description = "接口端点摘要")
    private String endpointSummary = "";

    @Schema(description = "系统展示标签")
    private List<String> modelTags = List.of();

    @Schema(description = "v2模板编码")
    private String templateCode = "";

    @Schema(description = "厂商名称")
    private String vendorName = "";

    @Schema(description = "产品/API名称")
    private String productName = "";

    @Schema(description = "能力展示名称")
    private String capabilityLabel = "";

    @Schema(description = "接入方式展示名称")
    private String accessModeLabel = "";

    @Schema(description = "部署形态展示名称")
    private String deploymentLabel = "";

    @Schema(description = "协议展示名称")
    private String protocolLabel = "";

    @Schema(description = "上游模型/资源字段展示名称")
    private String upstreamResourceLabel = "";

    @Schema(description = "凭据摘要字段")
    private List<String> credentialSummary = List.of();

    @Schema(description = "v2必填字段")
    private List<String> requiredFields = List.of();

    @Schema(description = "配置提示")
    private List<String> setupHints = List.of();

    @Schema(description = "已知限制")
    private List<String> knownLimitations = List.of();

    @Schema(description = "v2迁移状态")
    private String migrationStatus = "";

    @Schema(description = "是否默认配置(0否 1是)")
    private Integer isDefault;

    @Schema(description = "是否启用")
    private Integer isEnabled;

    @Schema(description = "测试状态(0未测试 1通过 2失败)")
    private Integer testStatus;

    @Schema(description = "测试结果说明")
    private String testMessage;

    @Schema(description = "测试时间")
    private Date testTime;

    @Schema(description = "模型配置(JSON格式)")
    private JSONObject configJson;

    @Schema(description = "官方文档链接")
    private String docLink;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "排序")
    private Integer sort;
}

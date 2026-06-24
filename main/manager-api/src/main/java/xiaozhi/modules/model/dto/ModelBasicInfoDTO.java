package xiaozhi.modules.model.dto;

import lombok.Data;

@Data
public class ModelBasicInfoDTO {
    private String id;
    private String modelName;
    private String modelCode;
    private String adapterCode;
    private String vendorName;
    private String productName;
    private String protocol;
    private String upstreamModelId;
}

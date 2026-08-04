package xiaozhi.modules.agent.dto;

import java.io.Serializable;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "上下文源配置DTO")
public class ContextProviderDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "URL地址")
    @Size(max = 2048)
    private String url;

    @Schema(description = "请求头")
    @Size(max = 20)
    private Map<String, Object> headers;
}

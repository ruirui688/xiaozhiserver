package xiaozhi.modules.device.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 设备更新DTO
 */
@Data
public class DeviceUpdateDTO implements Serializable {
    /**
    * 自动更新状态
    */
    @Max(1)
    @Min(0)
    private Integer autoUpdate;

    /**
    * 设备别名
    */
    @Size(max = 64)
    private String alias;

    /**
     * 语音输入路由策略
     */
    @Pattern(regexp = "server|device|auto", message = "语音输入路由仅支持server、device或auto")
    private String speechInputMode;

    /**
     * 语音输出路由策略
     */
    @Pattern(regexp = "server|device|auto", message = "语音输出路由仅支持server、device或auto")
    private String speechOutputMode;

    private static final long serialVersionUID = 1L;
}

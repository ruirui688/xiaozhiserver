package xiaozhi.modules.model.dto;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "模型测试结果")
public class ModelTestResultDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "是否测试通过")
    private Boolean success;

    @Schema(description = "测试结果说明")
    private String message;

    @Schema(description = "测试状态(0未测试 1通过 2失败)")
    private Integer testStatus;

    @Schema(description = "测试时间")
    private Date testTime;
}

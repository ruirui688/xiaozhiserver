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

    @Schema(description = "测试输入")
    private String testInput;

    @Schema(description = "测试输出")
    private String testOutput;

    @Schema(description = "测试图片预览 data URL")
    private String testImageDataUrl;

    @Schema(description = "测试级别: REAL_INFERENCE/CONNECTIVITY/CONFIG_ONLY/UNSUPPORTED")
    private String testLevel;

    @Schema(description = "测试级别说明")
    private String testLevelText;

    @Schema(description = "是否完成可标记通过的测试")
    private Boolean verified;

    @Schema(description = "响应耗时(毫秒)")
    private Long responseTimeMs;

    @Schema(description = "测试状态(0未测试 1通过 2失败)")
    private Integer testStatus;

    @Schema(description = "测试时间")
    private Date testTime;
}

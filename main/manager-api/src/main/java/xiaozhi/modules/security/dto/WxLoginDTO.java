package xiaozhi.modules.security.dto;

import java.io.Serializable;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "微信小程序登录表单")
public class WxLoginDTO implements Serializable {

    @Schema(description = "wx.login 返回的临时 code")
    @NotBlank(message = "微信登录code不能为空")
    private String code;
}

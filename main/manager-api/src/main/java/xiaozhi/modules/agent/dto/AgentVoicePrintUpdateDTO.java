package xiaozhi.modules.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改智能体声纹的dto
 *
 * @author zjy
 */
@Data
public class AgentVoicePrintUpdateDTO {
    /**
     * 智能体声纹id
     */
    @NotBlank
    private String id;
    /**
     * 音频文件id
     */
    private String audioId;
    /**
     * 声纹来源的人姓名
     */
    @NotBlank
    @Size(max = 50)
    private String sourceName;
    /**
     * 描述声纹来源的人
     */
    @Size(max = 200)
    private String introduce;
}

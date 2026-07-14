package xiaozhi.modules.agent.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 保存智能体声纹的dto
 *
 * @author zjy
 */
@Data
public class AgentVoicePrintSaveDTO {
    /**
     * 关联的智能体id
     */
    @NotBlank
    private String agentId;
    /**
     * 音频文件id
     */
    private String audioId;
    /**
     * 注册音频ID。设备采集流程默认 1 段，也兼容手动选择多段样本。
     */
    private List<String> audioIds;
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

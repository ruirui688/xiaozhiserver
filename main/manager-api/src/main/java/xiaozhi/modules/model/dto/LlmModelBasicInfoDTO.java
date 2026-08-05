package xiaozhi.modules.model.dto;

import java.util.List;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * LLM的模型的基础展示数据
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class LlmModelBasicInfoDTO extends ModelBasicInfoDTO{
    private String type;
    private String conversationMode;
    private Boolean realtime;
    private Boolean nativeFunctionCalling;
    private Boolean naturalBargeIn;
    private Boolean contextReset;
    private Boolean sessionVoice;
    private Boolean customVoiceIds;
    private String realtimeVoice;
    private List<String> realtimeVoiceOptions = List.of();
}

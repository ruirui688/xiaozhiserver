package xiaozhi.modules.agent.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

/**
 * 声纹识别接口返回的对象
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class IdentifyVoicePrintResponse {
    /**
     * 是否识别到匹配声纹。
     */
    private Boolean matched;
    /**
     * 业务侧用户id
     */
    @JsonProperty("user_id")
    private String userId;
    /**
     * 业务侧用户名称
     */
    @JsonProperty("user_name")
    private String userName;
    /**
     * 最匹配的声纹id
     */
    @JsonProperty("speaker_id")
    private String speakerId;
    /**
     * 最匹配的声纹名称
     */
    @JsonProperty("speaker_name")
    private String speakerName;
    /**
     * 最匹配的声纹说明
     */
    @JsonProperty("speaker_description")
    private String speakerDescription;
    /**
     * 声纹的分数
     */
    private Double score;
}

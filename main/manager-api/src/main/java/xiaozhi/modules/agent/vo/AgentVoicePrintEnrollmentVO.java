package xiaozhi.modules.agent.vo;

import java.util.Date;
import java.util.List;

import lombok.Data;

@Data
public class AgentVoicePrintEnrollmentVO {
    private String id;
    private String agentId;
    private String sourceName;
    private String introduce;
    private String deviceId;
    private Integer requiredSamples;
    private String status;
    private String voicePrintId;
    private Integer sampleCount;
    private Integer collectedCount;
    private Integer progress;
    private Date createDate;
    private Date updateDate;
    private List<AgentVoicePrintEnrollmentSampleVO> samples;
}

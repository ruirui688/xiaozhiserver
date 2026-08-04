package xiaozhi.modules.agent.vo;

import lombok.Data;

@Data
public class AgentTemplateSummaryVO {
    private String id;
    private String templateName;
    private String agentName;
    private Integer version;
    private String scope;
    private Boolean deletable;
}

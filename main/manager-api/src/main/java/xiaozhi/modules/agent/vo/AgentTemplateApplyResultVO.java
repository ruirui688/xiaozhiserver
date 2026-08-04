package xiaozhi.modules.agent.vo;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

@Data
public class AgentTemplateApplyResultVO {
    private AgentInfoVO agent;
    private List<String> tagNames = new ArrayList<>();
    private List<String> skippedItems = new ArrayList<>();
}

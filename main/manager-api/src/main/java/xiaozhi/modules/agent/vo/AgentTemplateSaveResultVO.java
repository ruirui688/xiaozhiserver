package xiaozhi.modules.agent.vo;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

@Data
public class AgentTemplateSaveResultVO {
    private AgentTemplateSummaryVO template;
    private List<String> warnings = new ArrayList<>();
}

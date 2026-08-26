package xiaozhi.modules.agent.vo;

import java.util.List;
import java.util.Map;

import lombok.Data;

@Data
public class AgentTemplatePreviewVO {
    private AgentTemplateSummaryVO template;
    private String systemPrompt;
    private String language;
    private Map<String, String> modelNames;
    private String voiceName;
    private List<String> tagNames;
    private List<String> skillNames;
    private List<String> capabilityNames;
    private Integer contextSourceCount;
    private List<String> skippedItems;
}

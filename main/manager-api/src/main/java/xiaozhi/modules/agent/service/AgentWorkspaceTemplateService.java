package xiaozhi.modules.agent.service;

import java.util.List;

import xiaozhi.modules.agent.dto.AgentTemplateApplyDTO;
import xiaozhi.modules.agent.dto.AgentTemplateCreateDTO;
import xiaozhi.modules.agent.dto.AgentTemplateSaveAsDTO;
import xiaozhi.modules.agent.dto.AgentTemplateReviewDTO;
import xiaozhi.modules.agent.vo.AgentTemplateApplyResultVO;
import xiaozhi.modules.agent.vo.AgentTemplateSaveResultVO;
import xiaozhi.modules.agent.vo.AgentTemplateSummaryVO;

public interface AgentWorkspaceTemplateService {
    List<AgentTemplateSummaryVO> listAccessible();

    AgentTemplateSaveResultVO saveAs(String agentId, AgentTemplateSaveAsDTO dto);

    AgentTemplateApplyResultVO apply(String agentId, String templateId, AgentTemplateApplyDTO dto);

    AgentTemplateApplyResultVO createFromTemplate(String templateId, AgentTemplateCreateDTO dto);

    void delete(String templateId);

    AgentTemplateSummaryVO submitForReview(String templateId);

    List<AgentTemplateSummaryVO> listReviewQueue();

    AgentTemplateSummaryVO review(String templateId, AgentTemplateReviewDTO dto);

    AgentTemplateSummaryVO publishOfficial(String templateId);
}

package xiaozhi.modules.agent.service;

import xiaozhi.modules.agent.dto.AgentVoicePrintEnrollmentCreateDTO;
import xiaozhi.modules.agent.dto.AgentVoicePrintEnrollmentSampleDTO;
import xiaozhi.modules.agent.vo.AgentVoicePrintEnrollmentVO;

public interface AgentVoicePrintEnrollmentService {
    AgentVoicePrintEnrollmentVO create(AgentVoicePrintEnrollmentCreateDTO dto);

    AgentVoicePrintEnrollmentVO get(String id);

    AgentVoicePrintEnrollmentVO addSample(String id, AgentVoicePrintEnrollmentSampleDTO dto);

    AgentVoicePrintEnrollmentVO addDeviceSample(String id, AgentVoicePrintEnrollmentSampleDTO dto);

    AgentVoicePrintEnrollmentVO complete(String id);

    AgentVoicePrintEnrollmentVO cancel(String id);

    String getAgentIdByEnrollmentId(String id);
}

package xiaozhi.modules.config.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.agent.dto.FeishuRuntimeActionDTO;
import xiaozhi.modules.agent.service.AgentFeishuService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/config/feishu")
public class FeishuRuntimeController {
    private final AgentFeishuService feishuService;

    @PostMapping("/{agentId}/actions")
    public Result<Map<String, Object>> execute(
            @PathVariable String agentId,
            @Valid @RequestBody FeishuRuntimeActionDTO dto) {
        return new Result<Map<String, Object>>().ok(feishuService.executeRuntimeAction(agentId, dto));
    }
}

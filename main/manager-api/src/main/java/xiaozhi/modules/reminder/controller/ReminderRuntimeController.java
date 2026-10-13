package xiaozhi.modules.reminder.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.reminder.dto.ReminderRuntimeActionDTO;
import xiaozhi.modules.reminder.service.ReminderService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/config/reminders")
public class ReminderRuntimeController {
    private final ReminderService reminderService;

    @PostMapping("/{agentId}/actions")
    public Result<Map<String, Object>> execute(
            @PathVariable String agentId,
            @Valid @RequestBody ReminderRuntimeActionDTO dto) {
        return new Result<Map<String, Object>>().ok(
                reminderService.executeRuntimeAction(agentId, dto));
    }
}

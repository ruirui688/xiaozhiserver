package xiaozhi.modules.reminder.controller;

import java.util.List;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.reminder.dto.ReminderSaveDTO;
import xiaozhi.modules.reminder.service.ReminderService;
import xiaozhi.modules.reminder.vo.ReminderDeviceVO;
import xiaozhi.modules.reminder.vo.ReminderVO;

@RestController
@RequiredArgsConstructor
@RequestMapping("/reminders")
@RequiresPermissions("sys:role:normal")
public class ReminderController {
    private final ReminderService reminderService;

    @GetMapping
    public Result<List<ReminderVO>> list(@RequestParam(required = false) String status) {
        return new Result<List<ReminderVO>>().ok(reminderService.list(status));
    }

    @GetMapping("/devices")
    public Result<List<ReminderDeviceVO>> devices() {
        return new Result<List<ReminderDeviceVO>>().ok(reminderService.listDevices());
    }

    @PostMapping
    public Result<ReminderVO> create(@Valid @RequestBody ReminderSaveDTO dto) {
        return new Result<ReminderVO>().ok(reminderService.create(dto));
    }

    @PutMapping("/{id}")
    public Result<ReminderVO> update(
            @PathVariable String id,
            @Valid @RequestBody ReminderSaveDTO dto) {
        return new Result<ReminderVO>().ok(reminderService.update(id, dto));
    }

    @DeleteMapping("/{id}")
    public Result<Void> cancel(@PathVariable String id) {
        reminderService.cancel(id);
        return new Result<Void>().ok(null);
    }
}

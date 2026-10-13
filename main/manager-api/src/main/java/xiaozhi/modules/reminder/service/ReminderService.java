package xiaozhi.modules.reminder.service;

import java.util.List;
import java.util.Map;

import xiaozhi.modules.reminder.dto.ReminderRuntimeActionDTO;
import xiaozhi.modules.reminder.dto.ReminderSaveDTO;
import xiaozhi.modules.reminder.vo.ReminderDeviceVO;
import xiaozhi.modules.reminder.vo.ReminderVO;

public interface ReminderService {
    List<ReminderVO> list(String status);

    List<ReminderDeviceVO> listDevices();

    ReminderVO create(ReminderSaveDTO dto);

    ReminderVO update(String id, ReminderSaveDTO dto);

    void cancel(String id);

    Map<String, Object> executeRuntimeAction(String agentId, ReminderRuntimeActionDTO dto);
}

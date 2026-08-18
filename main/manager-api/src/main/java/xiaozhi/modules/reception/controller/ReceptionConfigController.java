package xiaozhi.modules.reception.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.reception.dto.ReceptionContextRequestDTO;
import xiaozhi.modules.reception.dto.ReceptionVisitEventDTO;
import xiaozhi.modules.reception.service.ReceptionEventService;
import xiaozhi.modules.reception.service.ReceptionContextService;
import xiaozhi.modules.reception.vo.ReceptionContextVO;

@RestController
@RequiredArgsConstructor
@RequestMapping("/config/reception")
public class ReceptionConfigController {
    private final ReceptionContextService receptionContextService;
    private final ReceptionEventService receptionEventService;

    @PostMapping("/context")
    public Result<ReceptionContextVO> context(
            @Valid @RequestBody ReceptionContextRequestDTO request) {
        return new Result<ReceptionContextVO>().ok(receptionContextService.resolve(request));
    }

    @PostMapping("/events")
    public Result<Boolean> event(
            @Valid @RequestBody ReceptionVisitEventDTO request) {
        return new Result<Boolean>().ok(receptionEventService.recordVisit(request));
    }
}

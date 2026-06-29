package xiaozhi.modules.memory.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.memory.dto.MemoryEventDTO;
import xiaozhi.modules.memory.dto.MemoryForgetDTO;
import xiaozhi.modules.memory.dto.MemoryQueryDTO;
import xiaozhi.modules.memory.dto.MemoryRememberDTO;
import xiaozhi.modules.memory.service.MemoryService;
import xiaozhi.modules.memory.vo.MemoryQueryVO;

@RestController
@RequestMapping("config/memory")
@Tag(name = "服务端长期记忆")
@AllArgsConstructor
public class MemoryConfigController {
    private final MemoryService memoryService;

    @PostMapping("events")
    @Operation(summary = "服务端上报长期记忆事件")
    public Result<Void> recordEvent(@RequestBody MemoryEventDTO dto) {
        memoryService.recordEvent(dto);
        return new Result<Void>().ok(null);
    }

    @PostMapping("query")
    @Operation(summary = "服务端查询长期记忆上下文")
    public Result<MemoryQueryVO> query(@RequestBody MemoryQueryDTO dto) {
        return new Result<MemoryQueryVO>().ok(memoryService.query(dto));
    }

    @PostMapping("remember")
    @Operation(summary = "服务端显式写入长期记忆")
    public Result<Void> remember(@RequestBody MemoryRememberDTO dto) {
        memoryService.remember(dto, "system", null);
        return new Result<Void>().ok(null);
    }

    @PostMapping("forget")
    @Operation(summary = "服务端显式忘记长期记忆")
    public Result<Void> forget(@RequestBody MemoryForgetDTO dto) {
        memoryService.forget(dto, "system", null);
        return new Result<Void>().ok(null);
    }
}

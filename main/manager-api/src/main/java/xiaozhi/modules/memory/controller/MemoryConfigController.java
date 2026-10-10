package xiaozhi.modules.memory.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.config.support.ConfigLatency;
import xiaozhi.modules.memory.dto.MemoryEventDTO;
import xiaozhi.modules.memory.dto.MemoryForgetDTO;
import xiaozhi.modules.memory.dto.MemoryQueryDTO;
import xiaozhi.modules.memory.dto.MemoryRememberDTO;
import xiaozhi.modules.memory.dto.MemorySessionProcessDTO;
import xiaozhi.modules.memory.service.MemoryService;
import xiaozhi.modules.memory.service.MemoryRequestScopeService;
import xiaozhi.modules.memory.service.processing.MemorySessionTaskService;
import xiaozhi.modules.memory.vo.MemoryQueryVO;

@RestController
@RequestMapping("config/memory")
@Tag(name = "服务端长期记忆")
@AllArgsConstructor
public class MemoryConfigController {
    private final MemoryService memoryService;
    private final MemoryRequestScopeService scopes;
    private final MemorySessionTaskService sessionTasks;

    @PostMapping("events")
    @Operation(summary = "服务端上报长期记忆事件")
    public Result<Void> recordEvent(@RequestBody @Valid MemoryEventDTO dto) {
        scopes.require(dto.getAgentId(), dto.getMacAddress(), null);
        memoryService.recordEvent(dto);
        return new Result<Void>().ok(null);
    }

    @PostMapping("query")
    @Operation(summary = "服务端查询长期记忆上下文")
    public Result<MemoryQueryVO> query(@RequestBody @Valid MemoryQueryDTO dto) {
        scopes.require(dto.getAgentId(), dto.getMacAddress(), null);
        return ConfigLatency.request("memory.query", () -> new Result<MemoryQueryVO>().ok(memoryService.query(dto)));
    }

    @PostMapping("remember")
    @Operation(summary = "服务端显式写入长期记忆")
    public Result<Void> remember(@RequestBody @Valid MemoryRememberDTO dto) {
        scopes.require(dto.getAgentId(), dto.getMacAddress(), null);
        memoryService.remember(dto, "system", null);
        return new Result<Void>().ok(null);
    }

    @PostMapping("forget")
    @Operation(summary = "服务端显式忘记长期记忆")
    public Result<Void> forget(@RequestBody @Valid MemoryForgetDTO dto) {
        scopes.require(dto.getAgentId(), dto.getMacAddress(), null);
        memoryService.forget(dto, "system", null);
        return new Result<Void>().ok(null);
    }

    @PostMapping("process-session")
    @Operation(summary = "会话结束后由记忆大模型主动抽取长期记忆")
    public Result<Integer> processSession(@RequestBody @Valid MemorySessionProcessDTO dto) {
        scopes.require(dto.getAgentId(), dto.getMacAddress(), dto.getSessionId());
        return new Result<Integer>().ok(sessionTasks.process(dto));
    }

    @PostMapping("submit-session")
    @Operation(summary = "持久接收会话记忆分段，后台执行整理")
    public Result<java.util.Map<String, Object>> submitSession(@RequestBody @Valid MemorySessionProcessDTO dto) {
        return new Result<java.util.Map<String, Object>>().ok(sessionTasks.submit(dto));
    }

}

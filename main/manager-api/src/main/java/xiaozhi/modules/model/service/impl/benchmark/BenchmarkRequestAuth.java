package xiaozhi.modules.model.service.impl.benchmark;

import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import xiaozhi.common.constant.Constant;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.sys.service.SysParamsService;

@Component
@RequiredArgsConstructor
public class BenchmarkRequestAuth {

    private final SysParamsService sysParamsService;

    public void apply(HttpHeaders headers) {
        String serverSecret = sysParamsService.getValue(Constant.SERVER_SECRET, true);
        if (StringUtils.isBlank(serverSecret)) {
            throw new RenException("server.secret 未配置，拒绝发送包含模型凭据的评测请求");
        }
        headers.setBearerAuth(serverSecret);
    }
}

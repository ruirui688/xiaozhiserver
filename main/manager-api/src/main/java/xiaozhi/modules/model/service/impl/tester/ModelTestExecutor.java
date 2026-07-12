package xiaozhi.modules.model.service.impl.tester;

import cn.hutool.json.JSONObject;
import xiaozhi.modules.model.dto.ModelTestResultDTO;

public interface ModelTestExecutor {

    boolean supports(String modelType, String providerType);

    ModelTestResultDTO test(JSONObject configJson, String providerType);
}

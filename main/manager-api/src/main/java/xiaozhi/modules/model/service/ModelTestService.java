package xiaozhi.modules.model.service;

import xiaozhi.modules.model.dto.ModelTestResultDTO;
import xiaozhi.modules.model.entity.ModelConfigEntity;

public interface ModelTestService {
    ModelTestResultDTO testModel(ModelConfigEntity modelConfig);

    boolean isVerifiedModelTest(ModelTestResultDTO result);

    boolean shouldShowModelTestResponseTime(ModelTestResultDTO result);
}

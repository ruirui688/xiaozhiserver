package xiaozhi.modules.model.service;

import xiaozhi.modules.model.dto.ModelBenchmarkCreateResponseDTO;
import xiaozhi.modules.model.dto.ModelBenchmarkTaskDTO;
import xiaozhi.modules.model.dto.ModelPerformanceTestRequestDTO;

public interface ModelBenchmarkService {

    ModelBenchmarkCreateResponseDTO createBenchmarkTask(ModelPerformanceTestRequestDTO request);

    ModelBenchmarkTaskDTO getBenchmarkTask(String taskId);
}

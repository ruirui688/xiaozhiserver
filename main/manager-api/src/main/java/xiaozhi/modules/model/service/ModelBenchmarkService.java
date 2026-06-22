package xiaozhi.modules.model.service;

import java.util.List;

import xiaozhi.modules.model.dto.ModelBenchmarkCreateResponseDTO;
import xiaozhi.modules.model.dto.ModelBenchmarkTaskDTO;
import xiaozhi.modules.model.dto.ModelPerformanceTestRequestDTO;

public interface ModelBenchmarkService {

    ModelBenchmarkCreateResponseDTO createBenchmarkTask(ModelPerformanceTestRequestDTO request);

    ModelBenchmarkTaskDTO getBenchmarkTask(String taskId);

    List<ModelBenchmarkTaskDTO> listBenchmarkTasks(String modelType, Integer limit);
}

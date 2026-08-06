package xiaozhi.modules.model.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import xiaozhi.common.exception.RenException;
import xiaozhi.modules.model.dao.ModelBenchmarkResultDao;
import xiaozhi.modules.model.dao.ModelBenchmarkSampleDao;
import xiaozhi.modules.model.dao.ModelBenchmarkTaskDao;
import xiaozhi.modules.model.dto.ModelPerformanceTestRequestDTO;
import xiaozhi.modules.model.entity.ModelBenchmarkTaskEntity;
import xiaozhi.modules.model.service.impl.benchmark.BenchmarkCaseGenerator;
import xiaozhi.modules.model.service.impl.benchmark.BenchmarkContextBuilder;
import xiaozhi.modules.model.service.impl.benchmark.BenchmarkResultPersistence;
import xiaozhi.modules.model.service.impl.benchmark.BenchmarkTaskExecutor;
import xiaozhi.modules.model.service.impl.benchmark.PythonBenchmarkClient;

class ModelBenchmarkServiceImplTest {

    @Test
    void marksTaskFailedWhenBoundedQueueIsFull() {
        ModelBenchmarkTaskDao taskDao = mock(ModelBenchmarkTaskDao.class);
        BenchmarkTaskExecutor taskExecutor = mock(BenchmarkTaskExecutor.class);
        when(taskExecutor.submit(any())).thenReturn(false);
        ModelBenchmarkServiceImpl service = new ModelBenchmarkServiceImpl(
                taskDao,
                mock(ModelBenchmarkResultDao.class),
                mock(ModelBenchmarkSampleDao.class),
                mock(BenchmarkContextBuilder.class),
                mock(PythonBenchmarkClient.class),
                mock(BenchmarkResultPersistence.class),
                mock(BenchmarkCaseGenerator.class),
                taskExecutor);

        ModelPerformanceTestRequestDTO request = new ModelPerformanceTestRequestDTO();
        request.setModelType("ASR");
        request.setModelIds(List.of("ASR_test"));

        assertThrows(RenException.class, () -> service.createBenchmarkTask(request));
        verify(taskDao).insert(any(ModelBenchmarkTaskEntity.class));
        ArgumentCaptor<ModelBenchmarkTaskEntity> update = ArgumentCaptor.forClass(ModelBenchmarkTaskEntity.class);
        verify(taskDao).updateById(update.capture());
        assertEquals("FAILED", update.getValue().getStatus());
        assertEquals("专业评测任务队列已满", update.getValue().getSummary());
    }
}

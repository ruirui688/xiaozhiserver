package xiaozhi.modules.model.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.Test;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.model.dao.ModelConfigDao;
import xiaozhi.modules.model.entity.ModelConfigEntity;

class ModelTestEvidenceServiceTest {
    ModelConfigDao dao=mock(ModelConfigDao.class);
    ModelRuntimeAssembler assembler=mock(ModelRuntimeAssembler.class);
    ModelTestEvidenceService service=new ModelTestEvidenceService(dao,assembler);

    ModelConfigEntity model(int enabled) {
        var model=new ModelConfigEntity();model.setId("model");model.setModelType("LLM");
        model.setIsEnabled(enabled);model.setConfigJson(JSONUtil.parseObj("{\"model\":\"original\"}"));
        return model;
    }
    void current(ModelConfigEntity model) {
        when(dao.selectOne(any())).thenReturn(model);
        when(assembler.buildTypedConfig(model)).thenReturn(model.getConfigJson());
    }
    @Test void successfulRetestPreservesPublicationAndNeverPublishesAnUnpublishedModel() {
        for(int enabled:new int[]{0,1}) {
            clearInvocations(dao);
            var model=model(enabled);current(model);var evidence=new ModelConfigEntity();
            service.record("model",ModelTestEvidenceService.snapshot(model),evidence,true);
            assertNull(evidence.getIsEnabled());
            verify(dao).updateById(evidence);
        }
    }
    @Test void failedRetestDisablesModel() {
        var model=model(1);current(model);var evidence=new ModelConfigEntity();
        service.record("model",ModelTestEvidenceService.snapshot(model),evidence,false);
        assertEquals(0,evidence.getIsEnabled());
    }
    @Test void concurrentRevocationIsNotUndoneBySuccess() {
        var tested=model(1);var revoked=model(0);current(revoked);var evidence=new ModelConfigEntity();
        service.record("model",ModelTestEvidenceService.snapshot(tested),evidence,true);
        assertNull(evidence.getIsEnabled());
    }
    @Test void rejectsOldEvidenceAfterRuntimeConfigurationChanges() {
        var tested=model(1);var changed=model(0);
        changed.setConfigJson(JSONUtil.parseObj("{\"model\":\"changed\"}"));current(changed);
        assertEquals("MODEL_CONFIG_CHANGED_DURING_TEST",assertThrows(RenException.class,()->
                service.record("model",ModelTestEvidenceService.snapshot(tested),new ModelConfigEntity(),true)).getMessage());
        verify(dao,never()).updateById(any(ModelConfigEntity.class));
    }
    @Test void snapshotIsDetachedFromTestRuntimeMutation() {
        var model=model(1);var snapshot=ModelTestEvidenceService.snapshot(model);
        model.getConfigJson().set("model","mutated");
        assertNotEquals(snapshot,ModelTestEvidenceService.snapshot(model));
    }
}

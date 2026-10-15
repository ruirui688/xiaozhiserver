package xiaozhi.modules.hotword;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.model.dao.ModelConfigDao;
import xiaozhi.modules.model.entity.ModelConfigEntity;

class HotwordCredentialSourcesTest {
    final ModelConfigDao models = mock(ModelConfigDao.class);
    final HotwordChannelCredentials credentials = mock(HotwordChannelCredentials.class);
    final HotwordCredentialSources sources = new HotwordCredentialSources(models,credentials);

    @Test void sameKeyDeduplicatesAcrossAdaptersRegardlessOfEnableOrTestStatus() {
        var stream = model("stream","doubao_stream"," fixture ",0,0);
        var recording = model("recording","doubao_recording","fixture",1,1);
        when(models.selectList(any())).thenReturn(List.of(stream,recording));
        var result = sources.discover();
        assertEquals(1,result.sources().size());
        assertEquals(HotwordService.digest("fixture"),result.sources().get(0).id());
        assertEquals("火山引擎",result.sources().get(0).name());
        assertFalse(result.sources().get(0).toString().contains("fixture"));
        assertNull(sources.forModel("stream",true));
        assertEquals(sources.forModel("recording",true),sources.forModel("stream",false));
    }
    @Test void changedOrRemovedKeyCannotResolveOriginalSource() {
        var model = model("stream","doubao_stream","new-fixture",1,1);
        when(models.selectList(any())).thenReturn(List.of(model));
        assertThrows(RenException.class,()->sources.require(HotwordService.digest("old-fixture")));
    }
    @Test void unavailableConfigurationDoesNotHideHealthySources() {
        var broken = model("broken","doubao_stream","bad",1,1);
        var good = model("good","doubao_recording","fixture",1,1);
        when(credentials.keyForModel("broken")).thenThrow(new IllegalStateException("sensitive body"));
        when(models.selectList(any())).thenReturn(List.of(broken,good));
        assertTrue(sources.discover().unavailable());
        assertEquals(1,sources.discover().sources().size());
        assertNull(sources.forModel("broken",true));
    }
    @Test void unsupportedAndPlaceholderCredentialsAreExcluded() {
        var unrelated = model("other","funasr","fixture",1,1);
        var placeholder = model("placeholder","doubao_stream","你的API Key",1,1);
        when(credentials.keyForModel("placeholder")).thenThrow(new RenException("凭据不可用"));
        when(models.selectList(any())).thenReturn(List.of(unrelated,placeholder));
        assertTrue(sources.discover().sources().isEmpty());
    }
    private ModelConfigEntity model(String id,String adapter,String key,int enabled,int tested) {
        var model = new ModelConfigEntity(); model.setId(id); model.setModelType("asr");
        model.setAdapterCode(adapter); model.setIsEnabled(enabled); model.setTestStatus(tested);
        when(models.selectById(id)).thenReturn(model);
        when(credentials.keyForModel(id)).thenReturn(key.trim());
        return model;
    }
}

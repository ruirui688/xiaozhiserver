package xiaozhi.modules.platform.service;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.model.service.ModelConfigService;
import xiaozhi.modules.sys.dao.SysUserDao;
import xiaozhi.modules.sys.service.SysUserService;
import xiaozhi.modules.voiceclone.service.impl.VoiceCloneServiceImpl;
class PlatformUnmeteredExecutionTest {
    /** Voice cloning is a paid adapter without admission and settlement, so it must refuse before any work. */
    @Test void cloneRejectsBeforeLoadingAudioOrCallingProvider(){
        VoiceCloneServiceImpl service=new VoiceCloneServiceImpl(
            mock(ModelConfigService.class),mock(SysUserService.class),mock(SysUserDao.class),new ObjectMapper());
        RenException rejected=assertThrows(RenException.class,()->service.cloneAudio("fixture"));
        assertTrue(rejected.getMsg().contains("VOICE_CLONE"));
    }
}

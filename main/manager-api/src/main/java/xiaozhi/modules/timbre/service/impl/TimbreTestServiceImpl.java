package xiaozhi.modules.timbre.service.impl;

import java.util.Date;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.AllArgsConstructor;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.utils.ConvertUtils;
import xiaozhi.modules.model.dto.ModelTestResultDTO;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.ModelConfigService;
import xiaozhi.modules.model.service.ModelTestService;
import xiaozhi.modules.timbre.dao.TimbreDao;
import xiaozhi.modules.timbre.entity.TimbreEntity;
import xiaozhi.modules.timbre.service.TimbreTestService;

@Service
@AllArgsConstructor
public class TimbreTestServiceImpl implements TimbreTestService {

    private static final int MODEL_TEST_PASSED = 1;
    private static final int MODEL_TEST_FAILED = 2;

    private final TimbreDao timbreDao;
    private final ModelConfigService modelConfigService;
    private final ModelTestService modelTestService;

    @Override
    public ModelTestResultDTO test(String timbreId) {
        if (StringUtils.isBlank(timbreId)) {
            throw new RenException("音色 ID 不能为空");
        }

        TimbreEntity timbre = timbreDao.selectById(timbreId);
        if (timbre == null) {
            throw new RenException("音色不存在或已被删除");
        }
        if (StringUtils.isBlank(timbre.getTtsVoice())) {
            throw new RenException("音色编码不能为空");
        }

        ModelConfigEntity model = modelConfigService.getModelByIdFromCache(timbre.getTtsModelId());
        if (model == null) {
            throw new RenException("音色关联的 TTS 模型不存在");
        }
        if (!"TTS".equalsIgnoreCase(model.getModelType())) {
            throw new RenException("音色只能关联 TTS 模型");
        }
        if (model.getConfigJson() == null) {
            throw new RenException("音色关联的 TTS 模型运行配置为空");
        }

        ModelConfigEntity testModel = ConvertUtils.sourceToTarget(model, ModelConfigEntity.class);
        JSONObject testConfig = JSONUtil.parseObj(model.getConfigJson().toString());
        testConfig.set("private_voice", timbre.getTtsVoice());
        if (StringUtils.isNotBlank(timbre.getReferenceAudio())) {
            testConfig.set("ref_audio", timbre.getReferenceAudio());
        }
        if (StringUtils.isNotBlank(timbre.getReferenceText())) {
            testConfig.set("ref_text", timbre.getReferenceText());
        }
        if (StringUtils.isNotBlank(timbre.getLanguages())) {
            testConfig.set("language", timbre.getLanguages().split("、")[0].trim());
        }
        testModel.setConfigJson(testConfig);
        testModel.setModelName(model.getModelName() + " / " + timbre.getName());

        long startedAt = System.currentTimeMillis();
        ModelTestResultDTO result = modelTestService.testModel(testModel);
        if (result == null) {
            throw new RenException("音色真实合成测试没有返回结果");
        }
        if (result.getResponseTimeMs() == null && modelTestService.shouldShowModelTestResponseTime(result)) {
            result.setResponseTimeMs(System.currentTimeMillis() - startedAt);
        }

        boolean hasPlayableAudio = StringUtils.startsWithIgnoreCase(
                StringUtils.trim(result.getTestAudioDataUrl()),
                "data:audio/");
        boolean verified = modelTestService.isVerifiedModelTest(result) && hasPlayableAudio;
        result.setSuccess(verified);
        result.setVerified(verified);
        result.setTestStatus(verified ? MODEL_TEST_PASSED : MODEL_TEST_FAILED);
        result.setTestTime(new Date());
        String detail = verified
                ? StringUtils.defaultIfBlank(result.getMessage(), "真实音频已生成")
                : hasPlayableAudio
                        ? StringUtils.defaultIfBlank(result.getMessage(), "真实合成未通过")
                        : "厂商未返回可播放的测试音频"
                                + (StringUtils.isNotBlank(result.getMessage()) ? "；" + result.getMessage() : "");
        result.setMessage((verified ? "音色真实合成测试通过：" : "音色真实合成测试失败：") + detail);
        return result;
    }
}

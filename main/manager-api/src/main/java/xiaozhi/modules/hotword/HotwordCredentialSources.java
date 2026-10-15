package xiaozhi.modules.hotword;

import java.util.*;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.model.dao.ModelConfigDao;
import xiaozhi.modules.model.entity.ModelConfigEntity;

/** Resolves existing configured credentials in memory; hotword rows store only fingerprints. */
@Service
@RequiredArgsConstructor
public class HotwordCredentialSources {
    private final ModelConfigDao models;
    private final HotwordChannelCredentials credentials;

    public static final class Source {
        private final String id;
        private final String name;
        private final String key;
        Source(String id, String name, String key) { this.id = id; this.name = name; this.key = key; }
        public String id() { return id; }
        public String name() { return name; }
        String key() { return key; }
        @Override public String toString() { return "HotwordSource[" + name + "]"; }
    }

    public record Discovery(List<Source> sources, boolean unavailable) {}

    public Discovery discover() {
        Map<String, String> keys = new TreeMap<>();
        boolean unavailable = false;
        List<ModelConfigEntity> configured;
        try {
            configured = models.selectList(new LambdaQueryWrapper<ModelConfigEntity>()
                    .eq(ModelConfigEntity::getModelType, "ASR"));
        } catch (RuntimeException error) {
            return new Discovery(List.of(), true);
        }
        for (ModelConfigEntity model : configured) {
            if (!supported(model)) continue;
            try {
                String key = configuredKey(model, false);
                if (key != null) keys.put(HotwordService.digest(key), key);
            } catch (RuntimeException error) {
                // Never include raw configuration/decryption exception text in API responses.
                unavailable = true;
            }
        }
        List<Source> result = new ArrayList<>();
        keys.forEach((id, key) -> result.add(new Source(id,
                keys.size() == 1 ? "火山引擎" : "火山来源 " + (result.size() + 1), key)));
        return new Discovery(List.copyOf(result), unavailable);
    }

    public Source require(String sourceId) {
        return discover().sources().stream().filter(source -> source.id().equals(sourceId)).findFirst()
                .orElseThrow(() -> new RenException("词库来源暂不可用，请管理员恢复原火山配置后重试"));
    }

    /** null means unsupported/missing, including disabled or untested models when applying. */
    public String forModel(String modelId, boolean applying) {
        if (modelId == null || modelId.isBlank()) return null;
        try {
            String key = configuredKey(models.selectById(modelId), applying);
            return key == null ? null : HotwordService.digest(key);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    /** Saving a binding needs actionable, fixed errors instead of a null catch-all. */
    public String requireForModel(String modelId) {
        ModelConfigEntity model;
        try {
            model = modelId == null || modelId.isBlank() ? null : models.selectById(modelId);
        } catch (RuntimeException unavailable) {
            throw new RenException("识别模型配置暂不可用，请稍后重试");
        }
        if (!supported(model)) throw new RenException("当前识别模型不支持火山热词库，请选择豆包流式或录音识别模型");
        if (!Objects.equals(1, model.getIsEnabled())) throw new RenException("当前识别模型尚未启用，请联系平台管理员启用");
        if (!Objects.equals(1, model.getTestStatus())) throw new RenException("当前识别模型尚未测试通过，请联系平台管理员完成测试");
        return HotwordService.digest(credentials.keyForModel(modelId));
    }

    private String configuredKey(ModelConfigEntity model, boolean applying) {
        if (!supported(model)) return null;
        if (applying && (!Objects.equals(1, model.getIsEnabled()) || !Objects.equals(1, model.getTestStatus()))) return null;
        return credentials.keyForModel(model.getId());
    }

    private static boolean supported(ModelConfigEntity model) {
        return model != null && "ASR".equalsIgnoreCase(model.getModelType())
                && ("doubao_stream".equals(model.getAdapterCode()) || "doubao_recording".equals(model.getAdapterCode()));
    }
}

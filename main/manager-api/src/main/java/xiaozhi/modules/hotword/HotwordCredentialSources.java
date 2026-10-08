package xiaozhi.modules.hotword;

import java.util.*;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import cn.hutool.json.JSONObject;
import lombok.RequiredArgsConstructor;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.model.dao.ModelConfigDao;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.impl.ModelRuntimeAssembler;

/** Resolves existing configured credentials in memory; hotword rows store only fingerprints. */
@Service
@RequiredArgsConstructor
public class HotwordCredentialSources {
    private final ModelConfigDao models;
    private final ModelRuntimeAssembler runtime;

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

    private String configuredKey(ModelConfigEntity model, boolean applying) {
        if (model == null || !"ASR".equalsIgnoreCase(model.getModelType())) return null;
        if (applying && (!Objects.equals(1, model.getIsEnabled()) || !Objects.equals(1, model.getTestStatus()))) return null;
        JSONObject config = runtime.buildTypedConfig(model);
        if (config == null || !Set.of("doubao_stream", "doubao_recording")
                .contains(config.getStr("type", model.getAdapterCode()) == null ? "" : config.getStr("type", model.getAdapterCode()))) return null;
        String key = config.getStr("api_key");
        if (key == null || key.isBlank() || key.contains("你的") || key.contains("***")) return null;
        return key.trim();
    }
}

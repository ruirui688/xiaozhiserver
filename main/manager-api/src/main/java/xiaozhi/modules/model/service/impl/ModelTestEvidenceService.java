package xiaozhi.modules.model.service.impl;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.model.dao.ModelConfigDao;
import xiaozhi.modules.model.entity.ModelConfigEntity;

/** Records evidence without turning a successful health check into a publication change. */
@Service
@RequiredArgsConstructor
public class ModelTestEvidenceService {
    private final ModelConfigDao models;
    private final ModelRuntimeAssembler assembler;

    public record Snapshot(List<Object> configuration, Map<?, ?> runtime) {}

    public static Snapshot snapshot(ModelConfigEntity model) {
        return new Snapshot(Arrays.asList(model.getModelType(), model.getModelCode(), model.getAdapterCode(),
                model.getTemplateCode(), model.getProtocol(), model.getUpstreamModelId(), model.getEndpointUrl(),
                model.getUpdateDate()), model.getConfigJson() == null ? null : JSONUtil.toBean(model.getConfigJson().toString(), Map.class));
    }

    @Transactional
    public void record(String id, Snapshot tested, ModelConfigEntity evidence, boolean verified) {
        // Config edits update this row before writing runtime fields in the same transaction.
        ModelConfigEntity current = models.selectOne(new QueryWrapper<ModelConfigEntity>().eq("id", id).last("FOR UPDATE"));
        if (current == null) throw new RenException("MODEL_CONFIG_CHANGED_DURING_TEST");
        current.setConfigJson(assembler.buildTypedConfig(current));
        if (!Objects.equals(tested, snapshot(current))) throw new RenException("MODEL_CONFIG_CHANGED_DURING_TEST");
        // Never set 1 here: unpublished models still require approval, and concurrent revocation stays effective.
        evidence.setIsEnabled(verified ? null : 0);
        models.updateById(evidence);
    }
}

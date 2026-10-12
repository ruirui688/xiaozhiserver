package xiaozhi.modules.model.service;

import java.util.List;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.page.PageData;
import xiaozhi.modules.model.dao.ModelProviderDao;
import xiaozhi.modules.model.dto.ModelProviderDTO;
import xiaozhi.modules.model.entity.ModelProviderEntity;
import xiaozhi.modules.security.ops.OpsAccess;

/** Provider management is shared by two OPS jobs, never by tenant sessions. */
@Service
@RequiredArgsConstructor
public class ModelProviderOperationsService {
    private final ModelProviderService providers;
    private final ModelProviderDao dao;

    public PageData<ModelProviderDTO> page(ModelProviderDTO filter, String page, String limit) {
        require("read", filter.getModelType());
        return providers.getListPage(filter, page, limit);
    }

    @Transactional(rollbackFor = Exception.class)
    public ModelProviderDTO add(ModelProviderDTO input) {
        require("write", input.getModelType());
        return providers.add(input);
    }

    @Transactional(rollbackFor = Exception.class)
    public ModelProviderDTO edit(ModelProviderDTO input) {
        require("write", input.getModelType());
        // Lock the stored type until the update finishes: a Plugin payload cannot
        // authorize an LLM row, nor race another operator changing its type.
        require("write", locked(input.getId()).getModelType());
        return providers.edit(input);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(List<String> ids) {
        require("write", "Plugin");
        if (ids == null || ids.isEmpty() || ids.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new RenException("删除的供应器ID不能为空");
        }
        // Authorize the complete batch before deleting mappings or any provider.
        for (String id : ids.stream().distinct().sorted().toList()) {
            require("write", locked(id).getModelType());
        }
        providers.delete(ids);
    }

    private ModelProviderEntity locked(String id) {
        ModelProviderEntity row = dao.selectOne(new QueryWrapper<ModelProviderEntity>().eq("id", id).last("FOR UPDATE"));
        if (row == null) throw new RenException(ErrorCode.NO_PERMISSION);
        return row;
    }

    private void require(String action, String modelType) {
        OpsAccess.employee();
        if (OpsAccess.has("model.catalog." + action, "PLATFORM", "models")) return;
        if (!"Plugin".equals(modelType)) throw new RenException(ErrorCode.NO_PERMISSION);
        OpsAccess.require("prompt.catalog." + action, "PLATFORM", "official-resources");
    }
}

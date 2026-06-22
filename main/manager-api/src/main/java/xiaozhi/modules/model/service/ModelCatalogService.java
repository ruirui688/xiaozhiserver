package xiaozhi.modules.model.service;

import java.util.List;

import cn.hutool.json.JSONObject;
import xiaozhi.modules.model.dto.ModelCatalogDTO;
import xiaozhi.modules.model.dto.ModelMigrationPreviewDTO;
import xiaozhi.modules.model.entity.ModelConfigEntity;

public interface ModelCatalogService {

    List<ModelCatalogDTO> getCatalog(String modelType, String providerCode, String capability, String vendor);

    List<ModelCatalogDTO> getTemplates(String modelType, String providerCode);

    ModelCatalogDTO resolve(ModelConfigEntity entity);

    ModelCatalogDTO resolve(String modelType, String providerCode, String modelCode, String modelName,
            JSONObject configJson);

    List<ModelMigrationPreviewDTO> previewMigration(String modelType);
}

package xiaozhi.modules.model.service;

import xiaozhi.modules.model.dto.ModelAssistantRequestDTO;
import xiaozhi.modules.model.dto.ModelAssistantResponseDTO;
import xiaozhi.modules.model.dto.ModelAssistantSettingsDTO;

public interface ModelAssistantService {

    ModelAssistantSettingsDTO getSettings();

    ModelAssistantSettingsDTO updateSettings(ModelAssistantSettingsDTO settings);

    ModelAssistantResponseDTO chat(ModelAssistantRequestDTO request);
}

package xiaozhi.modules.timbre.service;

import xiaozhi.modules.model.dto.ModelTestResultDTO;

/**
 * Executes a real synthesis test for one saved TTS voice.
 */
public interface TimbreTestService {

    ModelTestResultDTO test(String timbreId);
}

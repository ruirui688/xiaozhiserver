package xiaozhi.modules.device.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

class DeviceUpdateDTOValidationTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsSupportedSpeechRoutingModes() {
        for (String mode : new String[] { "server", "device", "auto" }) {
            DeviceUpdateDTO dto = new DeviceUpdateDTO();
            dto.setSpeechInputMode(mode);
            dto.setSpeechOutputMode(mode);
            assertTrue(validator.validate(dto).isEmpty(), mode);
        }
    }

    @Test
    void rejectsUnknownSpeechRoutingMode() {
        DeviceUpdateDTO dto = new DeviceUpdateDTO();
        dto.setSpeechInputMode("cloud");
        dto.setSpeechOutputMode("DEVICE");

        assertEquals(2, validator.validate(dto).size());
    }
}

package xiaozhi.modules.reminder.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class ReminderRuntimeActionDTOTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void canonicalDeviceIdCarriesRuntimeMacAddress() throws Exception {
        ReminderRuntimeActionDTO dto = objectMapper.readValue(
                "{\"action\":\"list\",\"deviceId\":\"AA:BB:CC\"}",
                ReminderRuntimeActionDTO.class);

        assertEquals("AA:BB:CC", dto.getDeviceId());
    }

    @Test
    void legacyMacAddressAliasRemainsAccepted() throws Exception {
        ReminderRuntimeActionDTO dto = objectMapper.readValue(
                "{\"action\":\"list\",\"macAddress\":\"AA:BB:CC\"}",
                ReminderRuntimeActionDTO.class);

        assertEquals("AA:BB:CC", dto.getDeviceId());
    }
}

package xiaozhi.modules.hotword;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import xiaozhi.common.exception.RenException;
class VolcengineHotwordClientTest {
    VolcengineHotwordClient client = new VolcengineHotwordClient(new ObjectMapper());
    @Test void rejectsHttpAndVendorErrorsWithoutEchoingSecrets() {
        assertThrows(RenException.class,()->client.parseResponse(403,"private-token"));
        RenException error = assertThrows(RenException.class,()->client.parseResponse(200,"{\"ResponseMetadata\":{\"Error\":{\"Message\":\"private-token\"}},\"Result\":{}}"));
        assertFalse(error.getMessage().contains("private-token"));
        assertThrows(RenException.class,()->client.parseResponse(200,"{\"Result\":null}"));
        assertThrows(RenException.class,()->client.parseResponse(200,"not-json"));
    }
    @Test void acceptsActualNestedTableResponse() {
        assertEquals("table",client.parseResponse(200,"{\"Result\":{\"BoostingTable\":{\"BoostingTableID\":\"table\"}}}").path("BoostingTable").path("BoostingTableID").asText());
    }
}

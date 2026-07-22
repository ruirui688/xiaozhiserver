package xiaozhi.modules.knowledge.rag.impl.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import xiaozhi.common.exception.RenException;
import xiaozhi.modules.knowledge.dto.dataset.DatasetDTO;

class KnowledgeBaseParserConfigCodecTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void decodesRagParserFieldsFromPersistedJson() {
        DatasetDTO.ParserConfig config = KnowledgeBaseParserConfigCodec.decodeDatasetConfig(
                "{\"chunk_token_num\":768,\"delimiter\":\"\\n\",\"llm_enabled\":true}", objectMapper);

        assertEquals(768, config.getChunkTokenNum());
        assertEquals("\n", config.getDelimiter());
    }

    @Test
    void rejectsMalformedJsonInsteadOfSilentlyDroppingConfig() {
        assertThrows(RenException.class,
                () -> KnowledgeBaseParserConfigCodec.decodeDatasetConfig("not-json", objectMapper));
    }
}

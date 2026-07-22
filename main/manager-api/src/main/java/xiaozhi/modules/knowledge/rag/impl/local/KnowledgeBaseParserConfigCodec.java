package xiaozhi.modules.knowledge.rag.impl.local;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import xiaozhi.common.exception.RenException;
import xiaozhi.modules.knowledge.dto.dataset.DatasetDTO;

/**
 * Converts the persisted JSON parser config at the knowledge-base boundary.
 */
public final class KnowledgeBaseParserConfigCodec {
    private KnowledgeBaseParserConfigCodec() {
    }

    public static DatasetDTO.ParserConfig decodeDatasetConfig(String parserConfig, ObjectMapper objectMapper) {
        if (StringUtils.isBlank(parserConfig)) {
            return null;
        }
        try {
            return objectMapper.readValue(parserConfig, DatasetDTO.ParserConfig.class);
        } catch (Exception e) {
            throw new RenException("parserConfig不是有效的JSON对象", e);
        }
    }
}

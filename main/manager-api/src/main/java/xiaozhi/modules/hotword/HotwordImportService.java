package xiaozhi.modules.hotword;

import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import xiaozhi.common.exception.RenException;

/** Shared supplier-account enumeration is disabled until independent credentials are supported. */
@Service
public class HotwordImportService {
    static boolean canImport() { return false; }
    public Map<String,Object> remote() { throw unavailable(); }
    public Map<String,Object> importRemote(HotwordImportDTO dto) { throw unavailable(); }
    private static RenException unavailable() { return new RenException("供应商词库导入尚未接入独立凭据，请使用工作空间热词编辑或批量粘贴词条"); }

    static List<HotwordSaveDTO.Entry> parseEntries(JsonNode table) {
        JsonNode file = table.path("File");
        String text;
        if (file.isTextual()) text = file.asText();
        else {
            JsonNode preview = table.path("Preview");
            if (!preview.isArray()) throw new RenException("火山未返回完整词表");
            List<String> lines = new ArrayList<>();
            for (JsonNode word : preview) {
                if (!word.isTextual() || word.asText().contains("\n") || word.asText().contains("\r"))
                    throw new RenException("远端词条格式异常");
                lines.add(word.asText());
            }
            // Some API versions omit File and weights; default 4 is used only for future publications.
            text = String.join("\n", lines);
        }
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        List<HotwordSaveDTO.Entry> entries = new ArrayList<>();
        for (String line : text.split("\\R")) {
            if (line.isBlank()) continue;
            String[] parts = line.split("\\|", -1);
            if (parts.length > 2) throw new RenException("远端词条格式异常");
            int weight = 4;
            if (parts.length == 2) {
                try { weight = Integer.parseInt(parts[1].trim()); }
                catch (NumberFormatException e) { throw new RenException("远端词条权重格式异常"); }
            }
            entries.add(new HotwordSaveDTO.Entry(parts[0], weight));
        }
        if (table.path("WordCount").asInt(-1) != entries.size()) throw new RenException("远端词条数量不完整，导入已停止");
        return HotwordService.normalize(entries);
    }
}

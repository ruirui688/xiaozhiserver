package xiaozhi.modules.knowledge.rag.impl.local;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import cn.hutool.core.text.csv.CsvData;
import cn.hutool.core.text.csv.CsvReadConfig;
import cn.hutool.core.text.csv.CsvReader;

/**
 * 本地知识库文档预解析器。
 *
 * <p>这里只做确定性解析，不调用模型。输出既可直接落为切片，也可作为 LLM 增强的输入。</p>
 */
public final class LocalKnowledgeDocumentParser {
    private static final Set<String> STRUCTURED_EXTENSIONS =
            Set.of("csv", "xlsx", "xls", "json", "jsonl", "qa");
    private static final Set<String> QUESTION_HEADERS = Set.of("question", "q", "问题", "问");
    private static final Set<String> ANSWER_HEADERS = Set.of("answer", "a", "答案", "答", "回复");

    private final ObjectMapper objectMapper;
    private final int maxChunkChars;

    public LocalKnowledgeDocumentParser(ObjectMapper objectMapper, int maxChunkChars) {
        this.objectMapper = objectMapper;
        this.maxChunkChars = maxChunkChars;
    }

    public ParsedDocument parse(String filename, InputStream inputStream) throws Exception {
        String extension = extension(filename);
        List<SourceChunk> chunks = switch (extension) {
            case "xlsx", "xls" -> parseExcel(inputStream);
            case "csv" -> parseCsv(inputStream);
            case "json" -> parseJson(inputStream);
            case "jsonl" -> parseJsonLines(inputStream);
            case "qa" -> parseQa(inputStream);
            default -> parseText(inputStream);
        };
        String modelInput = chunks.stream()
                .map(SourceChunk::content)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.joining("\n\n"));
        return new ParsedDocument(modelInput, List.copyOf(chunks), STRUCTURED_EXTENSIONS.contains(extension));
    }

    private List<SourceChunk> parseExcel(InputStream inputStream) throws Exception {
        List<SourceChunk> chunks = new ArrayList<>();
        DataFormatter formatter = new DataFormatter();
        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            for (Sheet sheet : workbook) {
                List<List<String>> rows = new ArrayList<>();
                for (Row row : sheet) {
                    List<String> values = new ArrayList<>();
                    int lastCell = Math.max(0, row.getLastCellNum());
                    for (int i = 0; i < lastCell; i++) {
                        Cell cell = row.getCell(i);
                        values.add(cell == null ? "" : formatter.formatCellValue(cell).trim());
                    }
                    if (values.stream().anyMatch(StringUtils::isNotBlank)) {
                        rows.add(values);
                    }
                }
                chunks.addAll(rowsToChunks(sheet.getSheetName(), rows));
            }
        }
        return chunks;
    }

    private List<SourceChunk> parseCsv(InputStream inputStream) throws Exception {
        CsvReadConfig config = CsvReadConfig.defaultConfig()
                .setContainsHeader(true)
                .setSkipEmptyRows(true)
                .setTrimField(true);
        try (CsvReader reader = new CsvReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8), config)) {
            CsvData data = reader.read();
            List<List<String>> rows = new ArrayList<>();
            rows.add(data.getHeader());
            data.getRows().forEach(row -> rows.add(row.getRawList()));
            return rowsToChunks("CSV", rows);
        }
    }

    private List<SourceChunk> parseJson(InputStream inputStream) throws Exception {
        JsonNode root = objectMapper.readTree(inputStream);
        List<SourceChunk> chunks = new ArrayList<>();
        if (root == null || root.isNull()) {
            return chunks;
        }
        if (root.isArray()) {
            int index = 0;
            for (JsonNode item : root) {
                addJsonNode(chunks, "JSON项目" + (++index), item);
            }
        } else {
            addJsonNode(chunks, "JSON", root);
        }
        return chunks;
    }

    private List<SourceChunk> parseJsonLines(InputStream inputStream) throws Exception {
        List<SourceChunk> chunks = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (StringUtils.isBlank(line)) {
                    continue;
                }
                addJsonNode(chunks, "JSONL第" + lineNumber + "行", objectMapper.readTree(line));
            }
        }
        return chunks;
    }

    private void addJsonNode(List<SourceChunk> chunks, String source, JsonNode node) {
        if (node != null && node.isObject()) {
            Map<String, String> fields = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> iterator = node.fields();
            while (iterator.hasNext()) {
                Map.Entry<String, JsonNode> entry = iterator.next();
                fields.put(entry.getKey(), entry.getValue().isTextual()
                        ? entry.getValue().asText()
                        : entry.getValue().toString());
            }
            addFieldChunk(chunks, source, fields);
            return;
        }
        addSplitChunks(chunks, null, null, source + "：" + node.asText(node.toString()));
    }

    private List<SourceChunk> parseQa(InputStream inputStream) throws Exception {
        String text = readText(inputStream);
        List<SourceChunk> chunks = new ArrayList<>();
        for (String block : text.split("\\R\\s*\\R")) {
            String question = null;
            String answer = null;
            for (String line : block.split("\\R")) {
                String trimmed = line.trim();
                if (startsWithLabel(trimmed, "问题", "问", "question", "q")) {
                    question = afterLabel(trimmed);
                } else if (startsWithLabel(trimmed, "答案", "答", "answer", "a")) {
                    answer = afterLabel(trimmed);
                }
            }
            if (StringUtils.isNoneBlank(question, answer)) {
                addSplitChunks(chunks, question, answer, "问题：" + question + "\n答案：" + answer);
            } else if (StringUtils.isNotBlank(block)) {
                addSplitChunks(chunks, null, null, block.trim());
            }
        }
        return chunks;
    }

    private List<SourceChunk> parseText(InputStream inputStream) throws Exception {
        List<SourceChunk> chunks = new ArrayList<>();
        addSplitChunks(chunks, null, null, readText(inputStream));
        return chunks;
    }

    private List<SourceChunk> rowsToChunks(String sourceName, List<List<String>> rows) {
        if (rows == null || rows.size() < 2) {
            return List.of();
        }
        List<String> header = rows.get(0);
        int questionIndex = findHeader(header, QUESTION_HEADERS);
        int answerIndex = findHeader(header, ANSWER_HEADERS);
        List<SourceChunk> chunks = new ArrayList<>();
        for (int rowIndex = 1; rowIndex < rows.size(); rowIndex++) {
            List<String> row = rows.get(rowIndex);
            Map<String, String> fields = new LinkedHashMap<>();
            for (int col = 0; col < row.size(); col++) {
                String value = row.get(col);
                if (StringUtils.isBlank(value)) {
                    continue;
                }
                String key = col < header.size() && StringUtils.isNotBlank(header.get(col))
                        ? header.get(col).trim()
                        : "列" + (col + 1);
                fields.put(key, value.trim());
            }
            if (fields.isEmpty()) {
                continue;
            }
            String question = getCell(row, questionIndex);
            String answer = getCell(row, answerIndex);
            addFieldChunk(chunks, sourceName + " 第" + (rowIndex + 1) + "行", fields, question, answer);
        }
        return chunks;
    }

    private void addFieldChunk(List<SourceChunk> chunks, String source, Map<String, String> fields) {
        String question = findValue(fields, QUESTION_HEADERS);
        String answer = findValue(fields, ANSWER_HEADERS);
        addFieldChunk(chunks, source, fields, question, answer);
    }

    private void addFieldChunk(List<SourceChunk> chunks, String source, Map<String, String> fields,
            String question, String answer) {
        String content = "来源：" + source + "\n" + fields.entrySet().stream()
                .map(entry -> entry.getKey() + "：" + entry.getValue())
                .collect(Collectors.joining("\n"));
        addSplitChunks(chunks, question, answer, content);
    }

    private void addSplitChunks(List<SourceChunk> target, String question, String answer, String content) {
        if (StringUtils.isBlank(content)) {
            return;
        }
        List<String> parts = new LocalKnowledgeTextSplitter(maxChunkChars).splitLlmSections(content.trim());
        for (String part : parts) {
            if (StringUtils.isNotBlank(part)) {
                target.add(new SourceChunk(
                        StringUtils.trimToNull(question), StringUtils.trimToNull(answer), part.trim()));
            }
        }
    }

    private int findHeader(List<String> headers, Set<String> candidates) {
        for (int i = 0; i < headers.size(); i++) {
            String normalized = StringUtils.lowerCase(StringUtils.trimToEmpty(headers.get(i)), Locale.ROOT);
            if (candidates.contains(normalized)) {
                return i;
            }
        }
        return -1;
    }

    private String findValue(Map<String, String> fields, Set<String> candidates) {
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            String normalized = StringUtils.lowerCase(StringUtils.trimToEmpty(entry.getKey()), Locale.ROOT);
            if (candidates.contains(normalized)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String getCell(List<String> row, int index) {
        return index >= 0 && index < row.size() ? StringUtils.trimToNull(row.get(index)) : null;
    }

    private boolean startsWithLabel(String value, String... labels) {
        String normalized = StringUtils.lowerCase(value, Locale.ROOT);
        for (String label : labels) {
            if (normalized.matches("^" + label + "\\s*[:：].*")) {
                return true;
            }
        }
        return false;
    }

    private String afterLabel(String value) {
        return StringUtils.trimToEmpty(value.replaceFirst("^[^:：]+[:：]", ""));
    }

    private String readText(InputStream inputStream) throws Exception {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n")).replaceFirst("^\\uFEFF", "");
        }
    }

    private String extension(String filename) {
        String value = StringUtils.trimToEmpty(filename);
        int dot = value.lastIndexOf('.');
        return dot < 0 ? "" : value.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public record ParsedDocument(String modelInput, List<SourceChunk> directChunks, boolean structured) {
    }

    public record SourceChunk(String question, String answer, String content) {
    }
}

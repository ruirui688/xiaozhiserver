package xiaozhi.modules.hotword;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.*;
import lombok.RequiredArgsConstructor;
import xiaozhi.common.exception.RenException;

/** Fixed official endpoint; never forwards provider URLs, response messages or credentials. */
@Component
@RequiredArgsConstructor
public class VolcengineHotwordClient {
    private static final String ENDPOINT = "https://openspeech.bytedance.com/api/proxy/invoke/";
    private static final String VERSION = "2022-08-30";
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public String find(String key, String name) {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        for (int page = 1; page <= 50; page++) {
            if (System.nanoTime() > deadline) throw new RenException("查询词表超时，请稍后重试");
            JsonNode result = json(key, "ListBoostingTable", Map.of("PageNumber", page, "PageSize", 100, "PreviewSize", 0));
            JsonNode tables = result.path("BoostingTables");
            if (!tables.isArray()) throw new RenException("火山词表列表响应格式异常");
            for (JsonNode table : tables) {
                if (name.equals(table.path("BoostingTableName").asText()))
                    return requireId(table.path("BoostingTableID").asText());
            }
            if (tables.size() < 100) return null;
        }
        throw new RenException("火山词表数量超出查询范围，请联系管理员");
    }

    public String create(String key, String name, String contents) {
        JsonNode result = multipart(key, "CreateBoostingTable", name, null, contents);
        String id = result.path("BoostingTableID").asText("");
        if (id.isBlank()) id = result.path("BoostingTable").path("BoostingTableID").asText("");
        if (id.isBlank()) id = find(key, name);
        return requireId(id);
    }

    public void update(String key, String name, String id, String contents) {
        multipart(key, "UpdateBoostingTable", name, requireId(id), contents);
    }

    public void verify(String key, String id, List<HotwordSaveDTO.Entry> entries) {
        JsonNode result = json(key, "GetBoostingTable", Map.of("BoostingTableID", requireId(id), "PreviewSize", 5000));
        JsonNode table = result.has("BoostingTable") ? result.path("BoostingTable") : result;
        if (!id.equals(table.path("BoostingTableID").asText()) || table.path("WordCount").asInt(-1) != entries.size())
            throw new RenException("火山词表核对未通过，请重试发布");
        Set<String> expected = new HashSet<>();
        entries.forEach(entry -> expected.add(entry.word()));
        Set<String> actual = new HashSet<>();
        JsonNode preview = table.path("Preview");
        if (preview.isArray()) preview.forEach(word -> actual.add(word.asText().split("\\|", 2)[0].trim()));
        if (!actual.equals(expected)) throw new RenException("火山词条内容核对未通过，请重试发布");
    }

    public void delete(String key, String id) {
        json(key, "DeleteBoostingTable", Map.of("BoostingTableID", requireId(id)));
    }

    private JsonNode json(String key, String action, Map<String, Object> values) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>(values);
            payload.put("Action", action);
            payload.put("Version", VERSION);
            return send(key, action, "application/json", mapper.writeValueAsBytes(payload));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RenException("热词请求编码失败");
        }
    }

    private JsonNode multipart(String key, String action, String name, String id, String contents) {
        String boundary = "hotword" + UUID.randomUUID().toString().replace("-", "");
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("Action", action);
        fields.put("Version", VERSION);
        fields.put("BoostingTableName", name);
        if (id != null) fields.put("BoostingTableID", id);
        StringBuilder body = new StringBuilder();
        fields.forEach((field, value) -> body.append("--").append(boundary)
                .append("\r\nContent-Disposition: form-data; name=\"").append(field)
                .append("\"\r\n\r\n").append(value).append("\r\n"));
        body.append("--").append(boundary)
                .append("\r\nContent-Disposition: form-data; name=\"File\"; filename=\"hotwords.txt\"")
                .append("\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n")
                .append(contents).append("\r\n--").append(boundary).append("--\r\n");
        return send(key, action, "multipart/form-data; boundary=" + boundary,
                body.toString().getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode send(String key, String action, String contentType, byte[] body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT + "?Action=" + action + "&Version=" + VERSION))
                    .timeout(Duration.ofSeconds(30)).header("X-Api-Key", key).header("Content-Type", contentType)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return parseResponse(response.statusCode(), response.body());
        } catch (RenException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RenException("火山请求中断，请稍后重试（将核对已有词表）");
        } catch (Exception e) {
            throw new RenException("火山请求失败或超时，请稍后重试（将核对已有词表）");
        }
    }

    JsonNode parseResponse(int status, String body) {
        if (status < 200 || status >= 300) throw new RenException("火山热词接口请求失败（HTTP " + status + "）");
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode error = root.path("ResponseMetadata").path("Error");
            if (!error.isMissingNode() && !error.isNull() && !error.isEmpty())
                throw new RenException("火山热词接口拒绝请求，请检查模型凭据、服务权限或配额");
            if (!root.path("Result").isObject()) throw new RenException("火山热词接口响应格式异常");
            return root.path("Result");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RenException("火山热词接口响应格式异常");
        }
    }

    private String requireId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,128}")) throw new RenException("火山未返回有效词表标识，请重试核对");
        return id;
    }
}

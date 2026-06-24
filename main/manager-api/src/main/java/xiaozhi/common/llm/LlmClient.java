package xiaozhi.common.llm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * OpenAI 兼容接口的 LLM 客户端
 * <p>
 * 封装统一的 chat/completions 调用逻辑（HTTP 头、鉴权、异常处理），
 * 消除各模块中重复的内联 RestTemplate 代码。
 * </p>
 */
@Slf4j
@Component
public class LlmClient {

    private final RestTemplate restTemplate;

    public LlmClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setReadTimeout(30000);
        factory.setConnectTimeout(30000);
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * 调用 OpenAI 兼容的 chat/completions 接口
     *
     * @param endpoint 完整的 chat/completions URL（例如 https://api.openai.com/v1/chat/completions）
     * @param apiKey   API 密钥（用于 Bearer 鉴权）
     * @param model    模型名称
     * @param messages 消息列表，每项包含 role 和 content
     * @param options  可选参数（temperature、max_tokens、stream、thinking、reasoning_effort 等）
     * @return 解析后的 JSON 响应（Map 形式）
     * @throws LlmException 调用失败或返回非 2xx 状态码时抛出
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> chatCompletion(
            String endpoint,
            String apiKey,
            String model,
            List<Map<String, String>> messages,
            Map<String, Object> options) {

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", messages);

        if (options != null && !options.isEmpty()) {
            if (options.containsKey("temperature")) {
                requestBody.put("temperature", options.get("temperature"));
            }
            if (options.containsKey("max_tokens")) {
                requestBody.put("max_tokens", options.get("max_tokens"));
            }
            if (options.containsKey("stream")) {
                requestBody.put("stream", options.get("stream"));
            }
            if (options.containsKey("thinking")) {
                requestBody.put("thinking", options.get("thinking"));
            }
            if (options.containsKey("reasoning_effort")) {
                requestBody.put("reasoning_effort", options.get("reasoning_effort"));
            }
            // 透传其他自定义参数
            for (Map.Entry<String, Object> entry : options.entrySet()) {
                if (!requestBody.containsKey(entry.getKey())) {
                    requestBody.put(entry.getKey(), entry.getValue());
                }
            }
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        log.debug("LLM 请求: endpoint={}, model={}", endpoint, model);

        ResponseEntity<String> response;
        try {
            response = restTemplate.exchange(
                    endpoint,
                    HttpMethod.POST,
                    new HttpEntity<>(requestBody, headers),
                    String.class);
        } catch (Exception e) {
            throw new LlmException("LLM 调用异常: " + e.getMessage(), e);
        }

        if (!response.getStatusCode().is2xxSuccessful()) {
            String responseBody = response.getBody();
            String detail = responseBody != null ? responseBody : "(无响应体)";
            throw new LlmException("LLM 调用失败，HTTP 状态码: " + response.getStatusCode() + "，响应: " + detail);
        }

        try {
            return JSONUtil.parseObj(response.getBody());
        } catch (Exception e) {
            throw new LlmException("LLM 响应 JSON 解析失败: " + e.getMessage(), e);
        }
    }
}

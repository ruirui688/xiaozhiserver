package xiaozhi.modules.agent.controller;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.commons.lang3.StringUtils;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xiaozhi.common.constant.Constant;
import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.page.PageData;
import xiaozhi.common.redis.RedisKeys;
import xiaozhi.common.redis.RedisUtils;
import xiaozhi.common.user.UserDetail;
import xiaozhi.common.utils.DateUtils;
import xiaozhi.common.utils.JsonUtils;
import xiaozhi.common.utils.MessageUtils;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.agent.dto.AgentChatHistoryDTO;
import xiaozhi.modules.agent.dto.AgentChatHistoryReportDTO;
import xiaozhi.modules.agent.dto.AgentChatSessionDTO;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.agent.service.AgentChatAudioService;
import xiaozhi.modules.agent.service.AgentChatHistoryService;
import xiaozhi.modules.agent.service.AgentService;
import xiaozhi.modules.agent.service.biz.AgentChatHistoryBizService;
import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.modules.workspace.service.WorkspacePermissionService;

@Slf4j
@Tag(name = "智能体聊天历史管理")
@RequiredArgsConstructor
@RestController
@RequestMapping("/agent/chat-history")
public class AgentChatHistoryController {
    private final AgentChatHistoryBizService agentChatHistoryBizService;
    private final AgentChatHistoryService agentChatHistoryService;
    private final AgentChatAudioService agentChatAudioService;
    private final AgentService agentService;
    private final RedisUtils redisUtils;
    private final WorkspacePermissionService workspacePermissionService;

    /**
     * 小智服务聊天上报请求
     * <p>
     * 小智服务聊天上报请求，包含Base64编码的音频数据和相关信息。
     *
     * @param request 包含上传文件及相关信息的请求对象
     */
    @Operation(summary = "小智服务聊天上报请求")
    @PostMapping("/report")
    public Result<Boolean> uploadFile(@Valid @RequestBody AgentChatHistoryReportDTO request) {
        Boolean result = agentChatHistoryBizService.report(request);
        return new Result<Boolean>().ok(result);
    }

    /**
     * 获取聊天记录下载链接
     * 
     * @param agentId   智能体ID
     * @param sessionId 会话ID
     * @return UUID作为下载标识
     */
    @Operation(summary = "获取聊天记录下载链接")
    @RequiresPermissions("sys:role:normal")
    @PostMapping("/getDownloadUrl/{agentId}/{sessionId}")
    public Result<String> getDownloadUrl(@PathVariable("agentId") String agentId,
            @PathVariable("sessionId") String sessionId) {
        checkAgentPermission(agentId);

        // 生成UUID
        String uuid = UUID.randomUUID().toString();
        // 存储agentId和sessionId到Redis，格式为agentId:sessionId
        redisUtils.set(RedisKeys.getChatHistoryKey(uuid), agentId + ":" + sessionId);

        return new Result<String>().ok(uuid);
    }

    /**
     * 清空智能体聊天记录
     *
     * @param agentId 智能体ID
     */
    @Operation(summary = "清空智能体聊天记录")
    @RequiresPermissions("sys:role:normal")
    @DeleteMapping("/{agentId}")
    public Result<Void> deleteByAgentId(@PathVariable("agentId") String agentId) {
        checkAgentPermission(agentId);
        requireAgentWrite(agentId);
        agentChatHistoryService.deleteByAgentId(agentId, true, true);
        return new Result<Void>().ok(null);
    }

    /**
     * 删除指定会话聊天记录
     *
     * @param agentId   智能体ID
     * @param sessionId 会话ID
     */
    @Operation(summary = "删除指定会话聊天记录")
    @RequiresPermissions("sys:role:normal")
    @DeleteMapping("/{agentId}/session/{sessionId}")
    public Result<Void> deleteBySessionId(@PathVariable("agentId") String agentId,
            @PathVariable("sessionId") String sessionId) {
        checkAgentPermission(agentId);
        requireAgentWrite(agentId);
        agentChatHistoryService.deleteBySessionId(agentId, sessionId);
        return new Result<Void>().ok(null);
    }

    /**
     * 删除单条聊天消息
     *
     * @param agentId   智能体ID
     * @param messageId 消息ID
     */
    @Operation(summary = "删除单条聊天消息")
    @RequiresPermissions("sys:role:normal")
    @DeleteMapping("/{agentId}/message/{messageId}")
    public Result<Void> deleteByMessageId(@PathVariable("agentId") String agentId,
            @PathVariable("messageId") Long messageId) {
        checkAgentPermission(agentId);
        requireAgentWrite(agentId);
        agentChatHistoryService.deleteByMessageId(agentId, messageId);
        return new Result<Void>().ok(null);
    }

    /**
     * 下载本会话聊天记录
     * 
     * @param uuid     下载标识
     * @param response HTTP响应
     */
    @Operation(summary = "下载本会话聊天记录")
    @GetMapping("/download/{uuid}/current")
    public void downloadCurrentSession(@PathVariable("uuid") String uuid,
            HttpServletResponse response) {
        // 从Redis获取agentId和sessionId
        String agentSessionInfo = (String) redisUtils.get(RedisKeys.getChatHistoryKey(uuid));
        if (StringUtils.isBlank(agentSessionInfo)) {
            throw new RenException(ErrorCode.DOWNLOAD_LINK_EXPIRED);
        }

        try {
            // 解析agentId和sessionId
            String[] parts = agentSessionInfo.split(":");
            if (parts.length != 2) {
                throw new RenException(ErrorCode.DOWNLOAD_LINK_INVALID);
            }
            String agentId = parts[0];
            String sessionId = parts[1];

            // 执行下载
            downloadChatHistory(agentId, List.of(sessionId), response);
        } finally {
            // 下载完成后删除UUID，防止盗刷
            redisUtils.delete(RedisKeys.getChatHistoryKey(uuid));
        }
    }

    /**
     * 下载本会话及前20条会话聊天记录
     * 
     * @param uuid     下载标识
     * @param response HTTP响应
     */
    @Operation(summary = "下载本会话及前20条会话聊天记录")
    @GetMapping("/download/{uuid}/previous")
    public void downloadCurrentSessionWithPrevious(@PathVariable("uuid") String uuid,
            HttpServletResponse response) {
        // 从Redis获取agentId和sessionId
        String agentSessionInfo = (String) redisUtils.get(RedisKeys.getChatHistoryKey(uuid));
        if (StringUtils.isBlank(agentSessionInfo)) {
            throw new RenException(ErrorCode.DOWNLOAD_LINK_EXPIRED);
        }

        try {
            // 解析agentId和sessionId
            String[] parts = agentSessionInfo.split(":");
            if (parts.length != 2) {
                throw new RenException(ErrorCode.DOWNLOAD_LINK_INVALID);
            }
            String agentId = parts[0];
            String sessionId = parts[1];

            // 获取所有会话列表
            Map<String, Object> params = Map.of(
                    "agentId", agentId,
                    Constant.PAGE, 1,
                    Constant.LIMIT, 1000 // 获取足够多的会话
            );
            PageData<AgentChatSessionDTO> sessionPage = agentChatHistoryService.getSessionListByAgentId(params);
            List<AgentChatSessionDTO> allSessions = sessionPage.getList();

            // 查找当前会话在列表中的位置
            int currentIndex = -1;
            for (int i = 0; i < allSessions.size(); i++) {
                if (allSessions.get(i).getSessionId().equals(sessionId)) {
                    currentIndex = i;
                    break;
                }
            }

            // 如果找到了当前会话，收集当前会话及前20条会话ID
            List<String> sessionIdsToDownload = new ArrayList<>();
            if (currentIndex != -1) {
                // 从当前会话开始，向后（数组后面）取最多20条会话（包括当前会话）
                int endIndex = Math.min(allSessions.size() - 1, currentIndex + 20); // 确保不越界
                for (int i = currentIndex; i <= endIndex; i++) {
                    sessionIdsToDownload.add(allSessions.get(i).getSessionId());
                }
            }

            // 如果没有找到当前会话，至少下载当前会话
            if (sessionIdsToDownload.isEmpty()) {
                sessionIdsToDownload.add(sessionId);
            }
            downloadChatHistory(agentId, sessionIdsToDownload, response);
        } finally {
            // 下载完成后删除UUID，防止盗刷
            redisUtils.delete(RedisKeys.getChatHistoryKey(uuid));
        }
    }

    /**
     * 下载指定会话的聊天记录
     * 
     * @param agentId    智能体ID
     * @param sessionIds 会话ID列表
     * @param response   HTTP响应
     */
    private void downloadChatHistory(String agentId, List<String> sessionIds, HttpServletResponse response) {
        try {
            response.setContentType("application/zip");
            String fileName = URLEncoder.encode("chat-history-package.zip", StandardCharsets.UTF_8.toString());
            response.setHeader("Content-Disposition", "attachment;filename=" + fileName);

            try (ZipOutputStream zip = new ZipOutputStream(response.getOutputStream(), StandardCharsets.UTF_8)) {
                StringBuilder text = new StringBuilder();
                List<Map<String, Object>> sessions = new ArrayList<>();
                for (int i = 0; i < sessionIds.size(); i++) {
                    String sessionId = sessionIds.get(i);
                    List<AgentChatHistoryDTO> chatHistoryList = agentChatHistoryService
                            .getChatHistoryBySessionId(agentId, sessionId);
                    sessions.add(buildSessionExport(agentId, sessionId, chatHistoryList, text, zip));
                    if (i < sessionIds.size() - 1) {
                        text.append('\n');
                    }
                }

                addZipEntry(zip, "history.txt", text.toString().getBytes(StandardCharsets.UTF_8));
                addZipEntry(zip, "metadata.json", JsonUtils.toJsonString(buildMetadata(agentId, sessions))
                        .getBytes(StandardCharsets.UTF_8));
                addZipEntry(zip, "README.txt", buildReadme().getBytes(StandardCharsets.UTF_8));

                zip.finish();
            }
        } catch (IOException e) {
            log.error("Failed to export chat history", e);
        }
    }

    private Map<String, Object> buildSessionExport(
            String agentId,
            String sessionId,
            List<AgentChatHistoryDTO> messages,
            StringBuilder text,
            ZipOutputStream zip) throws IOException {
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("session_id", sessionId);
        session.put("message_count", messages.size());
        List<Map<String, Object>> exportedMessages = new ArrayList<>();
        session.put("messages", exportedMessages);

        if (!messages.isEmpty()) {
            Date firstMessageTime = messages.get(0).getCreatedAt();
            session.put("started_at", formatDate(firstMessageTime));
            text.append(formatDate(firstMessageTime)).append('\n');
        }

        Date previousMessageTime = null;
        int index = 1;
        List<Map<String, Object>> latencyMessages = new ArrayList<>();
        Map<String, Map<String, Object>> latencyTurns = new LinkedHashMap<>();
        for (AgentChatHistoryDTO message : messages) {
            String role = message.getChatType() == 1 ? MessageUtils.getMessage(ErrorCode.CHAT_ROLE_USER)
                    : MessageUtils.getMessage(ErrorCode.CHAT_ROLE_AGENT);
            String direction = message.getChatType() == 1 ? ">>" : "<<";
            String messageTimeStr = formatDate(message.getCreatedAt());
            MessageContent content = parseMessageContent(message.getContent());
            Map<String, Object> metrics = parseMetrics(message.getMetricsJson());
            AudioExport audio = exportMessageAudio(agentId, sessionId, index, message, zip);
            long gapMs = previousMessageTime == null || message.getCreatedAt() == null
                    ? 0
                    : message.getCreatedAt().getTime() - previousMessageTime.getTime();

            text.append("[")
                    .append(role)
                    .append("]-[")
                    .append(messageTimeStr)
                    .append("]")
                    .append(direction)
                    .append(":")
                    .append(content.displayText())
                    .append('\n');
            if (content.speakerName() != null || content.speakerId() != null || content.speakerScore() != null) {
                text.append("  voiceprint: speaker=")
                        .append(StringUtils.defaultString(content.speakerName()))
                        .append(", speaker_id=")
                        .append(StringUtils.defaultString(content.speakerId()))
                        .append(", score=")
                        .append(content.speakerScore() == null ? "" : content.speakerScore())
                        .append('\n');
            }
            if (StringUtils.isNotBlank(audio.path())) {
                text.append("  audio: ").append(audio.path()).append('\n');
            }
            String latencySummary = buildLatencyText(metrics);
            if (StringUtils.isNotBlank(latencySummary)) {
                text.append("  latency: ").append(latencySummary).append('\n');
            }

            exportedMessages.add(buildMessageMetadata(index, role, message, content, audio, gapMs, metrics));
            if (!metrics.isEmpty()) {
                Map<String, Object> latencyMessage = buildLatencyMessage(sessionId, index, role, message, metrics);
                latencyMessages.add(latencyMessage);
                mergeTurnLatency(latencyTurns, latencyMessage);
            }
            previousMessageTime = message.getCreatedAt();
            index++;
        }
        addLatencyEntries(zip, sessionId, latencyMessages, new ArrayList<>(latencyTurns.values()));

        return session;
    }

    private Map<String, Object> buildMessageMetadata(
            int index,
            String role,
            AgentChatHistoryDTO message,
            MessageContent content,
            AudioExport audio,
            long gapMs,
            Map<String, Object> metrics) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("index", index);
        metadata.put("role", role);
        metadata.put("chat_type", message.getChatType());
        metadata.put("created_at", formatDate(message.getCreatedAt()));
        metadata.put("mac_address", message.getMacAddress());
        metadata.put("content", content.displayText());
        metadata.put("raw_content", message.getContent());
        metadata.put("voiceprint", content.voiceprintMetadata());
        metadata.put("audio_id", message.getAudioId());
        metadata.put("audio_file", audio.path());
        metadata.put("audio_bytes", audio.bytes());
        metadata.put("audio_duration_ms", audio.durationMs());
        metadata.put("previous_message_gap_ms", gapMs);
        metadata.put("metrics", metrics);
        return metadata;
    }

    private Map<String, Object> buildMetadata(String agentId, List<Map<String, Object>> sessions) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("agent_id", agentId);
        metadata.put("exported_at", formatDate(new Date()));
        metadata.put("format", "chat-history-package-v2");
        metadata.put("sessions", sessions);
        metadata.put("latency_coverage", "新记录包含 metrics_json 时可导出 VAD、ASR、声纹、LLM、TTS 和 token 链路；旧记录该字段为空时仅保留消息时间、相邻消息间隔和音频时长。");
        return metadata;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMetrics(String rawMetrics) {
        if (StringUtils.isBlank(rawMetrics)) {
            return Map.of();
        }
        try {
            Object parsed = JsonUtils.parseObject(rawMetrics, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                Map<String, Object> metrics = new LinkedHashMap<>();
                map.forEach((key, value) -> metrics.put(String.valueOf(key), value));
                return metrics;
            }
        } catch (Exception ignored) {
        }
        return Map.of("parse_error", true, "raw_metrics", rawMetrics);
    }

    private String buildLatencyText(Map<String, Object> metrics) {
        if (metrics == null || metrics.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        addPart(parts, "turn_id", metrics.get("turn_id"));
        addPart(parts, "vad_ms", nested(metrics, "latency_ms", "vad", "inference_total_ms"));
        addPart(parts, "asr_ms", nested(metrics, "latency_ms", "asr", "total_ms"));
        addPart(parts, "voiceprint_ms", nested(metrics, "latency_ms", "voiceprint", "total_ms"));
        addPart(parts, "llm_first_chunk_ms", nested(metrics, "latency_ms", "llm", "first_chunk_ms"));
        addPart(parts, "llm_total_ms", nested(metrics, "latency_ms", "llm", "total_ms"));
        addPart(parts, "tts_first_audio_ms", nested(metrics, "latency_ms", "tts", "first_audio_ms"));
        addPart(parts, "tts_total_ms", nested(metrics, "latency_ms", "tts", "total_ms"));
        addPart(parts, "tokens", nested(metrics, "tokens", "total_tokens"));
        return String.join(", ", parts);
    }

    private void addPart(List<String> parts, String key, Object value) {
        if (value != null && StringUtils.isNotBlank(String.valueOf(value))) {
            parts.add(key + "=" + value);
        }
    }

    private Map<String, Object> buildLatencyMessage(
            String sessionId,
            int index,
            String role,
            AgentChatHistoryDTO message,
            Map<String, Object> metrics) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("session_id", sessionId);
        item.put("message_index", index);
        item.put("role", role);
        item.put("chat_type", message.getChatType());
        item.put("created_at", formatDate(message.getCreatedAt()));
        item.put("turn_id", metrics.get("turn_id"));
        item.put("message_role", metrics.get("message_role"));
        item.put("latency_ms", metrics.get("latency_ms"));
        item.put("tokens", metrics.get("tokens"));
        item.put("input", metrics.get("input"));
        item.put("llm", metrics.get("llm"));
        item.put("tts", metrics.get("tts"));
        item.put("routing", metrics.get("routing"));
        item.put("raw_metrics", metrics);
        return item;
    }

    @SuppressWarnings("unchecked")
    private void mergeTurnLatency(Map<String, Map<String, Object>> turns, Map<String, Object> message) {
        Object turnIdValue = message.get("turn_id");
        String turnId = turnIdValue == null ? "message-" + message.get("message_index") : String.valueOf(turnIdValue);
        Map<String, Object> turn = turns.computeIfAbsent(turnId, key -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("turn_id", key);
            value.put("messages", new ArrayList<Map<String, Object>>());
            return value;
        });
        ((List<Map<String, Object>>) turn.get("messages")).add(message);
        Object rawMetrics = message.get("raw_metrics");
        if (rawMetrics instanceof Map<?, ?> metrics) {
            Object messageRole = metrics.get("message_role");
            if ("user".equals(messageRole)) {
                turn.put("user_metrics", metrics);
            } else if ("assistant".equals(messageRole)) {
                turn.put("assistant_metrics", metrics);
            }
            turn.put("latency_summary", buildLatencySummary(turn));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildLatencySummary(Map<String, Object> turn) {
        Map<String, Object> summary = new LinkedHashMap<>();
        Map<String, Object> user = turn.get("user_metrics") instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        Map<String, Object> assistant = turn.get("assistant_metrics") instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        putIfNotNull(summary, "vad_inference_total_ms", nested(user, "latency_ms", "vad", "inference_total_ms"));
        putIfNotNull(summary, "asr_total_ms", nested(user, "latency_ms", "asr", "total_ms"));
        putIfNotNull(summary, "voiceprint_total_ms", nested(user, "latency_ms", "voiceprint", "total_ms"));
        putIfNotNull(summary, "llm_first_chunk_ms", nested(assistant, "latency_ms", "llm", "first_chunk_ms"));
        putIfNotNull(summary, "llm_total_ms", nested(assistant, "latency_ms", "llm", "total_ms"));
        putIfNotNull(summary, "tts_first_audio_ms", nested(assistant, "latency_ms", "tts", "first_audio_ms"));
        putIfNotNull(summary, "tts_total_ms", nested(assistant, "latency_ms", "tts", "total_ms"));
        putIfNotNull(summary, "prompt_tokens", nested(assistant, "tokens", "prompt_tokens"));
        putIfNotNull(summary, "completion_tokens", nested(assistant, "tokens", "completion_tokens"));
        putIfNotNull(summary, "total_tokens", nested(assistant, "tokens", "total_tokens"));
        putIfNotNull(summary, "token_source", nested(assistant, "tokens", "source"));
        return summary;
    }

    private void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private void addLatencyEntries(
            ZipOutputStream zip,
            String sessionId,
            List<Map<String, Object>> latencyMessages,
            List<Map<String, Object>> latencyTurns) throws IOException {
        if (latencyMessages.isEmpty() && latencyTurns.isEmpty()) {
            return;
        }
        String base = "latency/" + safeFileName(sessionId) + "/";
        addZipEntry(zip, base + "messages.json",
                JsonUtils.toJsonString(latencyMessages).getBytes(StandardCharsets.UTF_8));
        addZipEntry(zip, base + "turns.json",
                JsonUtils.toJsonString(latencyTurns).getBytes(StandardCharsets.UTF_8));
        addZipEntry(zip, base + "summary.csv",
                buildLatencyCsv(latencyMessages).getBytes(StandardCharsets.UTF_8));
    }

    private String buildLatencyCsv(List<Map<String, Object>> messages) {
        StringBuilder csv = new StringBuilder();
        csv.append("session_id,turn_id,message_index,role,created_at,vad_inference_total_ms,asr_total_ms,voiceprint_total_ms,llm_first_chunk_ms,llm_total_ms,tts_first_audio_ms,tts_total_ms,prompt_tokens,completion_tokens,total_tokens,token_source\n");
        for (Map<String, Object> message : messages) {
            Map<String, Object> metrics = message.get("raw_metrics") instanceof Map<?, ?> map
                    ? toStringKeyMap(map)
                    : Map.of();
            csv.append(csv(message.get("session_id"))).append(',')
                    .append(csv(message.get("turn_id"))).append(',')
                    .append(csv(message.get("message_index"))).append(',')
                    .append(csv(message.get("role"))).append(',')
                    .append(csv(message.get("created_at"))).append(',')
                    .append(csv(nested(metrics, "latency_ms", "vad", "inference_total_ms"))).append(',')
                    .append(csv(nested(metrics, "latency_ms", "asr", "total_ms"))).append(',')
                    .append(csv(nested(metrics, "latency_ms", "voiceprint", "total_ms"))).append(',')
                    .append(csv(nested(metrics, "latency_ms", "llm", "first_chunk_ms"))).append(',')
                    .append(csv(nested(metrics, "latency_ms", "llm", "total_ms"))).append(',')
                    .append(csv(nested(metrics, "latency_ms", "tts", "first_audio_ms"))).append(',')
                    .append(csv(nested(metrics, "latency_ms", "tts", "total_ms"))).append(',')
                    .append(csv(nested(metrics, "tokens", "prompt_tokens"))).append(',')
                    .append(csv(nested(metrics, "tokens", "completion_tokens"))).append(',')
                    .append(csv(nested(metrics, "tokens", "total_tokens"))).append(',')
                    .append(csv(nested(metrics, "tokens", "source"))).append('\n');
        }
        return csv.toString();
    }

    private String csv(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    @SuppressWarnings("unchecked")
    private Object nested(Map<String, Object> map, String... keys) {
        Object current = map;
        for (String key : keys) {
            if (!(current instanceof Map<?, ?> currentMap)) {
                return null;
            }
            current = currentMap.get(key);
        }
        return current;
    }

    private Map<String, Object> toStringKeyMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private AudioExport exportMessageAudio(
            String agentId,
            String sessionId,
            int index,
            AgentChatHistoryDTO message,
            ZipOutputStream zip) throws IOException {
        if (StringUtils.isBlank(message.getAudioId())
                || !agentChatHistoryService.isAudioOwnedByAgent(message.getAudioId(), agentId)) {
            return AudioExport.empty();
        }
        byte[] audio = agentChatAudioService.getAudio(message.getAudioId());
        if (audio == null || audio.length == 0) {
            return AudioExport.empty();
        }
        String role = message.getChatType() == 1 ? "user" : message.getChatType() == 2 ? "assistant" : "tool";
        String path = "audio/" + safeFileName(sessionId) + "/" + String.format("%03d_%s_%s.wav",
                index,
                role,
                safeFileName(message.getAudioId()));
        addZipEntry(zip, path, audio);
        return new AudioExport(path, audio.length, wavDurationMs(audio));
    }

    private void addZipEntry(ZipOutputStream zip, String path, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(path);
        zip.putNextEntry(entry);
        zip.write(data);
        zip.closeEntry();
    }

    private MessageContent parseMessageContent(String rawContent) {
        if (StringUtils.isBlank(rawContent)) {
            return new MessageContent(rawContent, rawContent, null, null, null);
        }
        try {
            Object parsed = JsonUtils.parseObject(rawContent, Object.class);
            if (parsed instanceof Map<?, ?> map && map.get("content") != null) {
                Object speakerScore = map.get("speaker_score");
                return new MessageContent(
                        rawContent,
                        String.valueOf(map.get("content")),
                        stringValue(map.get("speaker")),
                        stringValue(map.get("speaker_id")),
                        doubleValue(speakerScore));
            }
            return new MessageContent(rawContent, rawContent, null, null, null);
        } catch (Exception ignored) {
            return new MessageContent(rawContent, rawContent, null, null, null);
        }
    }

    private String stringValue(Object value) {
        String text = value == null ? null : String.valueOf(value);
        return StringUtils.isBlank(text) ? null : text;
    }

    private Double doubleValue(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Double.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private long wavDurationMs(byte[] audio) {
        if (audio == null || audio.length < 44
                || audio[0] != 'R' || audio[1] != 'I' || audio[2] != 'F' || audio[3] != 'F') {
            return 0L;
        }
        int channels = littleEndianUnsignedShort(audio, 22);
        int sampleRate = littleEndianInt(audio, 24);
        int bitsPerSample = littleEndianUnsignedShort(audio, 34);
        int dataIndex = findDataChunk(audio);
        if (channels <= 0 || sampleRate <= 0 || bitsPerSample <= 0 || dataIndex < 0 || dataIndex + 8 > audio.length) {
            return 0L;
        }
        int dataSize = littleEndianInt(audio, dataIndex + 4);
        double bytesPerSecond = sampleRate * channels * (bitsPerSample / 8.0);
        return bytesPerSecond <= 0 ? 0L : Math.round(dataSize * 1000.0 / bytesPerSecond);
    }

    private int findDataChunk(byte[] audio) {
        for (int i = 12; i <= audio.length - 8; i++) {
            if (audio[i] == 'd' && audio[i + 1] == 'a' && audio[i + 2] == 't' && audio[i + 3] == 'a') {
                return i;
            }
        }
        return -1;
    }

    private int littleEndianUnsignedShort(byte[] bytes, int offset) {
        if (offset + 1 >= bytes.length) {
            return 0;
        }
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    private int littleEndianInt(byte[] bytes, int offset) {
        if (offset + 3 >= bytes.length) {
            return 0;
        }
        return (bytes[offset] & 0xff)
                | ((bytes[offset + 1] & 0xff) << 8)
                | ((bytes[offset + 2] & 0xff) << 16)
                | ((bytes[offset + 3] & 0xff) << 24);
    }

    private String formatDate(Date date) {
        return date == null ? "" : DateUtils.format(date, DateUtils.DATE_TIME_PATTERN);
    }

    private String safeFileName(String value) {
        if (StringUtils.isBlank(value)) {
            return "unknown";
        }
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String buildReadme() {
        return """
                chat-history-package.zip 内容说明

                history.txt: 便于阅读的聊天文本，包含声纹识别摘要和音频文件路径。
                metadata.json: 结构化原始数据，包含原始 content、声纹识别结果、音频时长、相邻消息间隔和 metrics。
                latency/<session_id>/messages.json: 按消息导出的完整链路指标。
                latency/<session_id>/turns.json: 按 turn_id 聚合的用户输入、LLM、TTS 和 token 指标。
                latency/<session_id>/summary.csv: 可直接导入表格的延时摘要。
                audio/: 用户与智能体消息对应的 WAV 音频文件；只有历史记录中存在 audio_id 的消息才会导出。

                注意：旧聊天记录没有 metrics_json 时，只能看到消息时间、相邻消息间隔和音频时长；新上报记录会包含 VAD、ASR、声纹、LLM、TTS 和 token 链路。
                """;
    }

    private record MessageContent(
            String rawContent,
            String displayText,
            String speakerName,
            String speakerId,
            Double speakerScore) {
        Map<String, Object> voiceprintMetadata() {
            Map<String, Object> voiceprint = new LinkedHashMap<>();
            voiceprint.put("speaker", speakerName);
            voiceprint.put("speaker_id", speakerId);
            voiceprint.put("speaker_score", speakerScore);
            return voiceprint;
        }
    }

    private record AudioExport(String path, Integer bytes, Long durationMs) {
        static AudioExport empty() {
            return new AudioExport(null, null, null);
        }
    }

    private void checkAgentPermission(String agentId) {
        if (StringUtils.isBlank(agentId)) {
            throw new RenException(ErrorCode.CHAT_HISTORY_AGENT_ID_NOT_NULL);
        }
        UserDetail user = SecurityUser.getUser();
        if (!agentService.checkAgentPermission(agentId, user.getId())) {
            throw new RenException(ErrorCode.CHAT_HISTORY_NO_PERMISSION);
        }
    }

    private void requireAgentWrite(String agentId) {
        AgentEntity agent = agentService.getAgentById(agentId);
        if (agent == null) {
            throw new RenException(ErrorCode.AGENT_NOT_FOUND);
        }
        workspacePermissionService.requireWrite(agent.getWorkspaceId());
    }
}

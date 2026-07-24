package xiaozhi.modules.agent.voiceprint;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.core.type.TypeReference;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xiaozhi.common.constant.Constant;
import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.utils.JsonUtils;
import xiaozhi.modules.agent.dto.IdentifyVoicePrintResponse;
import xiaozhi.modules.sys.service.SysParamsService;

/**
 * 远程声纹服务客户端。
 *
 * <p>业务侧始终使用智能体 ID 作为 voiceprint-api 的 user_id，Token 只通过
 * Authorization Header 传输。旧版 {@code /voiceprint/health?key=...} 地址仍可读取，
 * 但会在发起请求前拆分成无密钥 URL 和 Bearer Token。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class VoiceprintApiClient {
    public static final String LEGACY_USER_ID = "default";

    private final RestTemplate restTemplate;
    private final SysParamsService sysParamsService;

    public record Endpoint(URI apiRoot, String token) {
        public URI endpoint(String path) {
            String suffix = path.startsWith("/") ? path : "/" + path;
            return URI.create(apiRoot.toString() + suffix);
        }
    }

    public boolean isConfigured() {
        String rawUrl = sysParamsService.getValue(Constant.SERVER_VOICE_PRINT, true);
        String rawToken = sysParamsService.getValue(Constant.SERVER_VOICEPRINT_TOKEN, true);
        return !isMissing(rawUrl) && !isMissing(resolveToken(rawUrl, rawToken));
    }

    public Endpoint getConfiguredEndpoint() {
        String rawUrl = sysParamsService.getValue(Constant.SERVER_VOICE_PRINT, true);
        String rawToken = sysParamsService.getValue(Constant.SERVER_VOICEPRINT_TOKEN, true);
        return resolveEndpoint(rawUrl, rawToken);
    }

    public void validateConfigurationChange(String paramCode, String paramValue) {
        if (!Constant.SERVER_VOICE_PRINT.equals(paramCode)
                && !Constant.SERVER_VOICEPRINT_TOKEN.equals(paramCode)) {
            return;
        }

        String rawUrl = Constant.SERVER_VOICE_PRINT.equals(paramCode)
                ? paramValue
                : sysParamsService.getValue(Constant.SERVER_VOICE_PRINT, true);
        String rawToken = Constant.SERVER_VOICEPRINT_TOKEN.equals(paramCode)
                ? paramValue
                : sysParamsService.getValue(Constant.SERVER_VOICEPRINT_TOKEN, true);

        // 允许管理员先保存 URL 或 Token，两个值齐全后再做真实健康检查。
        if (isMissing(rawUrl) || isMissing(resolveToken(rawUrl, rawToken))) {
            return;
        }

        Endpoint endpoint = resolveEndpoint(rawUrl, rawToken);
        HttpHeaders headers = bearerHeaders(endpoint.token());
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    endpoint.endpoint("health"), HttpMethod.GET, new HttpEntity<>(headers), String.class);
            if (response.getStatusCode() != HttpStatus.OK || !isHealthy(response.getBody())) {
                throw new RenException(ErrorCode.VOICEPRINT_INTERFACE_FORMAT_ERROR);
            }
        } catch (RenException e) {
            throw e;
        } catch (Exception e) {
            throw new RenException(ErrorCode.VOICEPRINT_INTERFACE_VALIDATION_FAILED, e);
        }
    }

    public Map<String, Object> registerVoiceprint(
            String remoteUserId,
            String speakerId,
            String speakerName,
            String speakerDescription,
            ByteArrayResource resource) {
        Endpoint endpoint = getConfiguredEndpoint();
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("user_id", remoteUserId);
        body.add("user_name", remoteUserId);
        body.add("speaker_id", speakerId);
        body.add("speaker_name", speakerName);
        if (StringUtils.isNotBlank(speakerDescription)) {
            body.add("speaker_description", speakerDescription);
        }
        body.add("file", resource);

        HttpHeaders headers = bearerHeaders(endpoint.token());
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    endpoint.endpoint("register"),
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    String.class);
            Map<String, Object> parsed = parseBody(response.getBody());
            if (response.getStatusCode() != HttpStatus.OK || !isSuccess(parsed)) {
                throw remoteFailure(
                        ErrorCode.VOICEPRINT_REGISTER_PROCESS_ERROR,
                        null,
                        parsed,
                        "声纹注册处理失败");
            }
            return parsed;
        } catch (RestClientResponseException e) {
            throw mapRemoteError("register", e, ErrorCode.VOICEPRINT_REGISTER_REQUEST_ERROR);
        } catch (RestClientException e) {
            throw new RenException(ErrorCode.VOICEPRINT_REGISTER_REQUEST_ERROR, e);
        }
    }

    public IdentifyVoicePrintResponse identifyVoiceprint(
            String remoteUserId,
            List<String> speakerIds,
            ByteArrayResource resource) {
        Endpoint endpoint = getConfiguredEndpoint();
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("user_id", remoteUserId);
        if (speakerIds != null && !speakerIds.isEmpty()) {
            body.add("speaker_ids", String.join(",", speakerIds));
        }
        body.add("file", resource);

        HttpHeaders headers = bearerHeaders(endpoint.token());
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    endpoint.endpoint("identify"),
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    String.class);
            if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
                throw new RenException(ErrorCode.VOICEPRINT_IDENTIFY_REQUEST_ERROR);
            }
            return JsonUtils.parseObject(response.getBody(), IdentifyVoicePrintResponse.class);
        } catch (RestClientResponseException e) {
            throw mapRemoteError("identify", e, ErrorCode.VOICEPRINT_IDENTIFY_REQUEST_ERROR);
        } catch (RestClientException e) {
            throw new RenException(ErrorCode.VOICEPRINT_IDENTIFY_REQUEST_ERROR, e);
        }
    }

    public void deleteVoiceprint(String remoteUserId, String speakerId) {
        Endpoint endpoint = getConfiguredEndpoint();
        URI requestUri = UriComponentsBuilder.fromUri(endpoint.endpoint(speakerId))
                .queryParam("user_id", remoteUserId)
                .build()
                .encode()
                .toUri();

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    requestUri,
                    HttpMethod.DELETE,
                    new HttpEntity<>(bearerHeaders(endpoint.token())),
                    String.class);
            if (response.getStatusCode() != HttpStatus.OK || !isSuccess(response.getBody())) {
                throw new RenException(ErrorCode.VOICEPRINT_UNREGISTER_PROCESS_ERROR);
            }
        } catch (RestClientResponseException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return;
            }
            throw mapRemoteError("delete", e, ErrorCode.VOICEPRINT_UNREGISTER_REQUEST_ERROR);
        } catch (RestClientException e) {
            throw new RenException(ErrorCode.VOICEPRINT_UNREGISTER_REQUEST_ERROR, e);
        }
    }

    static Endpoint resolveEndpoint(String rawUrl, String rawToken) {
        if (isMissing(rawUrl)) {
            throw new RenException(ErrorCode.VOICEPRINT_API_NOT_CONFIGURED);
        }

        String token = resolveToken(rawUrl, rawToken);
        if (isMissing(token)) {
            throw new RenException(ErrorCode.VOICEPRINT_TOKEN_NOT_CONFIGURED);
        }

        try {
            URI source = new URI(rawUrl.trim());
            String scheme = StringUtils.lowerCase(source.getScheme());
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                throw new RenException(ErrorCode.VOICEPRINT_URL_PROTOCOL_ERROR);
            }
            if (StringUtils.isBlank(source.getHost()) || source.getUserInfo() != null || source.getFragment() != null) {
                throw new RenException(ErrorCode.VOICEPRINT_URL_INVALID);
            }

            validatePublicTarget(source.getHost());

            String path = StringUtils.defaultString(source.getPath(), "");
            path = StringUtils.removeEnd(path, "/");
            for (String suffix : List.of("/health", "/docs", "/openapi.json")) {
                if (path.endsWith("/voiceprint" + suffix)) {
                    path = StringUtils.removeEnd(path, suffix);
                    break;
                }
            }
            if (StringUtils.isBlank(path)) {
                path = "/voiceprint";
            } else if (!path.endsWith("/voiceprint")) {
                path = path + "/voiceprint";
            }

            URI apiRoot = new URI(
                    scheme,
                    null,
                    source.getHost(),
                    source.getPort(),
                    path,
                    null,
                    null);
            return new Endpoint(apiRoot, token.trim());
        } catch (RenException e) {
            throw e;
        } catch (URISyntaxException e) {
            throw new RenException(ErrorCode.VOICEPRINT_API_URI_ERROR, e);
        }
    }

    private static String resolveToken(String rawUrl, String rawToken) {
        if (!isMissing(rawToken)) {
            return rawToken.trim();
        }
        if (isMissing(rawUrl)) {
            return null;
        }
        try {
            return UriComponentsBuilder.fromUriString(rawUrl.trim())
                    .build()
                    .getQueryParams()
                    .getFirst("key");
        } catch (Exception e) {
            return null;
        }
    }

    private static void validatePublicTarget(String host) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) {
                throw new RenException(ErrorCode.VOICEPRINT_URL_INVALID);
            }
            for (InetAddress address : addresses) {
                if (isForbiddenAddress(address)) {
                    throw new RenException(ErrorCode.VOICEPRINT_URL_LOCALHOST);
                }
            }
        } catch (UnknownHostException e) {
            throw new RenException(ErrorCode.VOICEPRINT_URL_INVALID, e);
        }
    }

    private static boolean isForbiddenAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }

        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            return first == 0
                    || (first == 100 && second >= 64 && second <= 127)
                    || (first == 198 && (second == 18 || second == 19))
                    || first >= 224;
        }

        return address instanceof Inet6Address
                && (Byte.toUnsignedInt(bytes[0]) & 0xfe) == 0xfc;
    }

    private static HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return headers;
    }

    private static boolean isHealthy(String body) {
        Map<String, Object> parsed = parseBody(body);
        return parsed != null && "healthy".equals(parsed.get("status"));
    }

    private static boolean isSuccess(String body) {
        return isSuccess(parseBody(body));
    }

    private static boolean isSuccess(Map<String, Object> parsed) {
        return parsed != null && Boolean.TRUE.equals(parsed.get("success"));
    }

    private static Map<String, Object> parseBody(String body) {
        if (StringUtils.isBlank(body)) {
            return null;
        }
        try {
            return JsonUtils.parseObject(body, new TypeReference<Map<String, Object>>() { });
        } catch (RuntimeException e) {
            return null;
        }
    }

    private RenException mapRemoteError(String operation, RestClientResponseException error, int fallbackCode) {
        String responseBody = error.getResponseBodyAsString(StandardCharsets.UTF_8);
        Map<String, Object> parsed = parseBody(responseBody);
        String remoteCode = parsed == null ? null : String.valueOf(parsed.get("code"));
        log.warn(
                "声纹服务请求失败: operation={}, status={}, code={}",
                operation,
                error.getStatusCode().value(),
                StringUtils.defaultIfBlank(remoteCode, "unknown"));
        if ("voiceprint_limit_reached".equals(remoteCode)) {
            return new RenException(ErrorCode.AGENT_VOICEPRINT_LIMIT_REACHED, error);
        }
        if ("delete".equals(operation)) {
            return new RenException(fallbackCode, error);
        }
        return remoteFailure(fallbackCode, error, parsed, "声纹服务请求失败");
    }

    @SuppressWarnings("unchecked")
    private RenException remoteFailure(
            int code,
            Throwable cause,
            Map<String, Object> parsed,
            String fallbackMessage) {
        Object detailValue = parsed == null ? null : parsed.get("detail");
        String detail = detailValue == null ? null : String.valueOf(detailValue);
        if ("null".equalsIgnoreCase(StringUtils.trimToEmpty(detail))) {
            detail = null;
        }
        Object quality = parsed == null ? null : parsed.get("quality");
        StringBuilder message = new StringBuilder(StringUtils.defaultIfBlank(detail, fallbackMessage));
        if (quality instanceof Map<?, ?> qualityMap) {
            Object score = qualityMap.get("score");
            Object level = qualityMap.get("level");
            Object warnings = qualityMap.get("warnings");
            if (score != null || level != null) {
                message.append("（质量");
                if (score != null) {
                    message.append("分=").append(score);
                }
                if (level != null) {
                    if (score != null) {
                        message.append("，");
                    }
                    message.append("等级=").append(level);
                }
                message.append("）");
            }
            if (warnings instanceof List<?> warningList && !warningList.isEmpty()) {
                message.append("：").append(StringUtils.join(warningList, "；"));
            }
        }
        RenException exception = cause == null ? new RenException(code) : new RenException(code, cause);
        exception.setMsg(message.toString());
        return exception;
    }

    private static boolean isMissing(String value) {
        return StringUtils.isBlank(value)
                || "null".equalsIgnoreCase(value.trim())
                || value.contains("你的");
    }
}

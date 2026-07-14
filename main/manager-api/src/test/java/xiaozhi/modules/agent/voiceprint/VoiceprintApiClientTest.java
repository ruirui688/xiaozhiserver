package xiaozhi.modules.agent.voiceprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.springframework.context.MessageSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import xiaozhi.common.constant.Constant;
import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.utils.MessageUtils;
import xiaozhi.modules.agent.dto.IdentifyVoicePrintResponse;
import xiaozhi.modules.sys.service.SysParamsService;

class VoiceprintApiClientTest {

    @BeforeEach
    void configureMessages() {
        MessageSource messageSource = mock(MessageSource.class);
        when(messageSource.getMessage(any(String.class), any(Object[].class), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        ReflectionTestUtils.setField(MessageUtils.class, "messageSource", messageSource);
    }

    @Test
    void resolvesExplicitTokenAndLegacyHealthUrl() {
        VoiceprintApiClient.Endpoint explicit = VoiceprintApiClient.resolveEndpoint(
                "https://8.8.8.8:8005", "secret-token");
        assertEquals("https://8.8.8.8:8005/voiceprint", explicit.apiRoot().toString());
        assertEquals("secret-token", explicit.token());

        VoiceprintApiClient.Endpoint legacy = VoiceprintApiClient.resolveEndpoint(
                "https://8.8.8.8:8005/voiceprint/health?key=legacy-token", null);
        assertEquals("https://8.8.8.8:8005/voiceprint", legacy.apiRoot().toString());
        assertEquals("legacy-token", legacy.token());
    }

    @Test
    void rejectsPrivateVoiceprintTargets() {
        RenException error = assertThrows(
                RenException.class,
                () -> VoiceprintApiClient.resolveEndpoint("http://127.0.0.1:8005", "token"));
        assertEquals(ErrorCode.VOICEPRINT_URL_LOCALHOST, error.getCode());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void identifySendsAgentScopeAsTrustedUserId() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        SysParamsService sysParamsService = configuredParams();
        VoiceprintApiClient client = new VoiceprintApiClient(restTemplate, sysParamsService);

        when(restTemplate.exchange(
                any(URI.class),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"speaker_id\":\"voice-1\",\"score\":0.91}"));

        IdentifyVoicePrintResponse result = client.identifyVoiceprint(
                "agent-a", List.of("voice-1"), audioResource());

        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        org.mockito.Mockito.verify(restTemplate).exchange(
                eq(URI.create("https://8.8.8.8:8005/voiceprint/identify")),
                eq(HttpMethod.POST),
                entityCaptor.capture(),
                eq(String.class));
        MultiValueMap<String, Object> body = (MultiValueMap<String, Object>) entityCaptor.getValue().getBody();
        assertEquals("agent-a", body.getFirst("user_id"));
        assertEquals("voice-1", body.getFirst("speaker_ids"));
        assertEquals("voice-1", result.getSpeakerId());
    }

    @Test
    void mapsRemoteLimitToAgentLimitError() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        VoiceprintApiClient client = new VoiceprintApiClient(restTemplate, configuredParams());
        HttpClientErrorException remoteError = HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST,
                "Bad Request",
                HttpHeaders.EMPTY,
                "{\"detail\":\"limit\",\"code\":\"voiceprint_limit_reached\"}"
                        .getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
        when(restTemplate.exchange(
                any(URI.class),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenThrow(remoteError);

        RenException error = assertThrows(
                RenException.class,
                () -> client.registerVoiceprint(
                        "agent-a",
                        "voice-6",
                        "用户A",
                        "描述",
                        audioResource()));
        assertEquals(ErrorCode.AGENT_VOICEPRINT_LIMIT_REACHED, error.getCode());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void registerSendsSingleFileAndReturnsQualityReport() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        VoiceprintApiClient client = new VoiceprintApiClient(restTemplate, configuredParams());

        when(restTemplate.exchange(
                any(URI.class),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok(
                        "{\"success\":true,\"quality\":{\"score\":0.86,\"level\":\"good\"}}"));

        Map<String, Object> result = client.registerVoiceprint(
                "agent-a",
                "voice-1",
                "用户A",
                "你的主人",
                audioResource());

        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        org.mockito.Mockito.verify(restTemplate).exchange(
                eq(URI.create("https://8.8.8.8:8005/voiceprint/register")),
                eq(HttpMethod.POST),
                entityCaptor.capture(),
                eq(String.class));
        MultiValueMap<String, Object> body = (MultiValueMap<String, Object>) entityCaptor.getValue().getBody();
        assertEquals("agent-a", body.getFirst("user_id"));
        assertEquals("agent-a", body.getFirst("user_name"));
        assertEquals("voice-1", body.getFirst("speaker_id"));
        assertEquals("用户A", body.getFirst("speaker_name"));
        assertEquals("你的主人", body.getFirst("speaker_description"));
        assertEquals("voiceprint.wav", ((ByteArrayResource) body.getFirst("file")).getFilename());
        assertEquals(null, body.get("files"));
        assertEquals(true, result.get("success"));
        assertEquals("good", ((Map<String, Object>) result.get("quality")).get("level"));
    }

    private SysParamsService configuredParams() {
        SysParamsService service = mock(SysParamsService.class);
        when(service.getValue(Constant.SERVER_VOICE_PRINT, true))
                .thenReturn("https://8.8.8.8:8005");
        when(service.getValue(Constant.SERVER_VOICEPRINT_TOKEN, true))
                .thenReturn("secret-token");
        return service;
    }

    private ByteArrayResource audioResource() {
        return new ByteArrayResource(new byte[] {1, 2, 3}) {
            @Override
            public String getFilename() {
                return "voiceprint.wav";
            }
        };
    }
}

package xiaozhi.modules.hotword;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.security.CredentialCipher;
import xiaozhi.common.user.UserDetail;
import xiaozhi.modules.agent.dto.AgentUpdateDTO;
import xiaozhi.modules.agent.service.impl.AgentServiceImpl;
import xiaozhi.modules.agent.support.ManagedAsrHotwordGuard;
import xiaozhi.modules.agent.vo.AgentInfoVO;
import xiaozhi.modules.model.dao.*;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.impl.ModelRuntimeAssembler;
import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.modules.workspace.service.WorkspacePermissionService;

/** Real SQL joins + AES-GCM + source/guard chain; isolated synthetic data, no vendor calls. */
class HotwordChannelCredentialsTest {
    final JdbcTemplate db = new JdbcTemplate(new DriverManagerDataSource(
            "jdbc:h2:mem:hotword_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
    final CredentialCipher cipher = new CredentialCipher("hotword-test-only-master-key-not-for-production");
    final ObjectMapper json = new ObjectMapper();
    final HotwordChannelCredentials credentials = new HotwordChannelCredentials(db, cipher, json);
    final ModelConfigDao models = mock(ModelConfigDao.class);
    final HotwordCredentialSources sources = new HotwordCredentialSources(models, credentials);
    final ManagedAsrHotwordGuard guard = new ManagedAsrHotwordGuard(db, sources);
    ModelConfigEntity model;

    @BeforeEach void setup() throws Exception {
        db.execute("CREATE TABLE ai_platform_channel(id VARCHAR(64) PRIMARY KEY,model_id VARCHAR(64) UNIQUE,provider VARCHAR(32),service_type VARCHAR(16),status VARCHAR(16),credential_id VARCHAR(64))");
        db.execute("CREATE TABLE ai_platform_channel_credential(id VARCHAR(64) PRIMARY KEY,channel_id VARCHAR(64),secret_ciphertext TEXT)");
        db.execute("CREATE TABLE ai_asr_hotword(id VARCHAR(64) PRIMARY KEY,workspace_id VARCHAR(64),credential_fingerprint VARCHAR(64),status VARCHAR(16),remote_id VARCHAR(64))");
        db.execute("CREATE TABLE ai_asr_hotword_revision(hotword_id VARCHAR(64),remote_id VARCHAR(64))");
        model = model("recording", "doubao_recording");
        when(models.selectList(any())).thenReturn(List.of(model));
        channel("recording", "channel-a", "key-a", "fixture-speech-key");
        db.update("INSERT INTO ai_asr_hotword VALUES('library','workspace',?,'PUBLISHED','remote-table')",
                HotwordService.digest("fixture-speech-key"));
    }

    @Test void migratedLibraryMatchesRealChannelWhilePublicRuntimeRemainsPlaceholder() {
        var assembler = new ModelRuntimeAssembler(mock(ModelConfigParamDao.class), mock(ModelConfigSecretDao.class),
                models, mock(ModelTemplateDao.class), mock(ModelTemplateFieldDao.class));
        assertEquals("PLATFORM_MANAGED", assembler.buildTypedConfig(model).getStr("api_key"));
        assertEquals(HotwordService.digest("fixture-speech-key"), sources.requireForModel("recording"));
        assertDoesNotThrow(() -> guard.requireAllowed("workspace", "recording", "remote-table", List.of()));
        assertEquals("remote-table", db.queryForObject("SELECT remote_id FROM ai_asr_hotword", String.class));
    }

    @Test void realRoleUpdateOnlyChangesPromptAndKeepsExistingHotwordSelection() {
        var role = new AgentInfoVO(); role.setId("agent"); role.setWorkspaceId("workspace");
        role.setAsrModelId("recording"); role.setAsrHotwordId("remote-table");
        role.setAsrHotwordIds(List.of("remote-table")); role.setSystemPrompt("before");
        var permission = mock(WorkspacePermissionService.class);
        var service = mock(AgentServiceImpl.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service, "workspacePermissionService", permission);
        ReflectionTestUtils.setField(service, "managedAsrHotwordGuard", guard);
        doReturn(role).when(service).getAgentById("agent");
        doReturn(true).when(service).updateById(role);
        var input = new AgentUpdateDTO(); input.setSystemPrompt("updated fixture prompt");
        try (var security = mockStatic(SecurityUser.class)) {
            security.when(SecurityUser::getUser).thenReturn(new UserDetail());
            service.updateAgentById("agent", input, false);
        }
        verify(permission).requireWrite("workspace");
        verify(service).updateById(role);
        assertEquals("updated fixture prompt", role.getSystemPrompt());
        assertEquals("remote-table", role.getAsrHotwordId());
        assertEquals(List.of("remote-table"), role.getAsrHotwordIds());
    }

    @Test void sourceOptionsNeverExposeKeyCiphertextOrPlatformChannelIdentity() throws Exception {
        var permission = mock(WorkspacePermissionService.class);
        when(permission.currentWorkspaceId()).thenReturn("workspace");
        var service = new HotwordService(db, json, permission, sources,
                mock(xiaozhi.modules.agent.dao.AgentDao.class), mock(VolcengineHotwordClient.class),
                mock(org.springframework.context.ApplicationEventPublisher.class),
                mock(org.springframework.transaction.PlatformTransactionManager.class));
        String response = json.writeValueAsString(service.sourceOptions());
        assertTrue(response.contains(HotwordService.digest("fixture-speech-key")));
        for (String privateValue : List.of("fixture-speech-key", "channel-a", "key-a", "secret_ciphertext", "v1:")) {
            assertFalse(response.contains(privateValue));
        }
    }

    @Test void sameCredentialAcrossAdaptersDeduplicatesAndSupportsHistoricalRevision() throws Exception {
        var stream = model("stream", "doubao_stream");
        channel("stream", "channel-b", "key-b", "fixture-speech-key");
        when(models.selectList(any())).thenReturn(List.of(model, stream));
        assertEquals(1, sources.discover().sources().size());
        db.update("INSERT INTO ai_asr_hotword_revision VALUES('library','old-remote')");
        assertDoesNotThrow(() -> guard.requireAllowed("workspace", "stream", "old-remote", List.of("remote-table")));
    }

    @Test void rotationToDifferentKeyIsNotCachedOrSilentlyRebound() throws Exception {
        assertDoesNotThrow(() -> guard.requireAllowed("workspace", "recording", "remote-table", null));
        credential("key-new", "channel-a", "different-fixture");
        db.update("UPDATE ai_platform_channel SET credential_id='key-new'");
        assertTrue(assertThrows(RenException.class, () -> guard.requireAllowed(
                "workspace", "recording", "remote-table", null)).getMessage().contains("来源不一致"));
        assertThrows(RenException.class, () -> sources.require(HotwordService.digest("fixture-speech-key")));
        assertEquals(HotwordService.digest("fixture-speech-key"), db.queryForObject(
                "SELECT credential_fingerprint FROM ai_asr_hotword", String.class));
    }

    @Test void disabledAndUntestedModelsHaveDistinctErrorsButDoNotHideUsableManagementSource() {
        model.setIsEnabled(0);
        assertTrue(assertThrows(RenException.class, () -> sources.requireForModel("recording")).getMessage().contains("尚未启用"));
        assertNull(sources.forModel("recording", true));
        assertEquals(1, sources.discover().sources().size());
        model.setIsEnabled(1); model.setTestStatus(0);
        assertTrue(assertThrows(RenException.class, () -> sources.requireForModel("recording")).getMessage().contains("尚未测试通过"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"status='DISABLED'", "provider='DEEPSEEK'", "service_type='TTS'", "credential_id='missing'"})
    void unusableChannelFailsClosed(String update) {
        db.update("UPDATE ai_platform_channel SET " + update);
        assertThrows(RenException.class, () -> sources.requireForModel("recording"));
        assertTrue(sources.discover().sources().isEmpty());
        assertTrue(sources.discover().unavailable());
    }

    @Test void credentialFromAnotherChannelCannotBeBorrowed() throws Exception {
        channel("other", "channel-other", "key-other", "fixture-speech-key");
        db.update("UPDATE ai_platform_channel SET credential_id='key-other' WHERE id='channel-a'");
        assertThrows(RenException.class, () -> credentials.keyForModel("recording"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PLATFORM_MANAGED", "  PLATFORM_MANAGED  ", "***", "你的API Key", " "})
    void placeholdersAreNeverSourcesOrRemoteCredentials(String key) throws Exception {
        db.update("UPDATE ai_platform_channel_credential SET secret_ciphertext=?",
                cipher.encrypt("platform-channel-credential:key-a", json.writeValueAsString(Map.of("api_key", key))));
        assertThrows(RenException.class, () -> credentials.keyForModel("recording"));
        assertTrue(sources.discover().sources().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-json-with-sensitive-fixture", "{\"api_key\":123}", "{\"api_key\":null}", "{}"})
    void badEncryptedPayloadUsesOnlyFixedErrorWithoutCause(String payload) {
        db.update("UPDATE ai_platform_channel_credential SET secret_ciphertext=?",
                cipher.encrypt("platform-channel-credential:key-a", payload));
        var error = assertThrows(RenException.class, () -> credentials.keyForModel("recording"));
        assertFalse(error.getMessage().contains(payload));
        assertNull(error.getCause());
    }

    @Test void wrongEncryptionScopeAndPlaintextAreRejected() {
        for (String encrypted : List.of("plaintext-fixture", cipher.encrypt("wrong-scope", "sensitive-fixture"))) {
            db.update("UPDATE ai_platform_channel_credential SET secret_ciphertext=?", encrypted);
            assertThrows(RenException.class, () -> credentials.keyForModel("recording"));
        }
    }

    @Test void ownershipAndDeletionChecksStillPrecedeSourceResolution() {
        assertTrue(assertThrows(RenException.class, () -> guard.requireAllowed(
                "other-workspace", "recording", "remote-table", null)).getMessage().contains("无权"));
        db.update("UPDATE ai_asr_hotword SET status='DELETING'");
        assertTrue(assertThrows(RenException.class, () -> guard.requireAllowed(
                "workspace", "recording", "remote-table", null)).getMessage().contains("正在删除"));
    }

    @Test void unknownFingerprintIsNotClaimedAndUnsupportedModelIsExplicit() {
        db.update("UPDATE ai_asr_hotword SET credential_fingerprint=NULL");
        assertTrue(assertThrows(RenException.class, () -> guard.requireAllowed(
                "workspace", "recording", "remote-table", null)).getMessage().contains("缺少可验证"));
        model.setAdapterCode("funasr");
        assertTrue(assertThrows(RenException.class, () -> sources.requireForModel("recording")).getMessage().contains("不支持"));
    }

    private ModelConfigEntity model(String id, String adapter) {
        var result = new ModelConfigEntity(); result.setId(id); result.setModelType("ASR");
        result.setAdapterCode(adapter); result.setIsEnabled(1); result.setTestStatus(1);
        when(models.selectById(id)).thenReturn(result);
        return result;
    }
    private void channel(String modelId, String channel, String credential, String key) throws Exception {
        db.update("INSERT INTO ai_platform_channel VALUES(?,?,'VOLCENGINE','ASR','ACTIVE',?)", channel, modelId, credential);
        credential(credential, channel, key);
    }
    private void credential(String id, String channel, String key) throws Exception {
        db.update("INSERT INTO ai_platform_channel_credential VALUES(?,?,?)", id, channel,
                cipher.encrypt("platform-channel-credential:" + id, json.writeValueAsString(Map.of("api_key", key))));
    }
}

package xiaozhi.modules.memory.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;
import cn.hutool.json.JSONUtil;
import xiaozhi.modules.memory.dao.MemoryForgetTombstoneDao;
import xiaozhi.modules.memory.dao.MemoryRevisionDao;
import xiaozhi.modules.memory.dto.MemoryFactDTO;
import xiaozhi.modules.memory.dto.MemoryPolicyDTO;
import xiaozhi.modules.memory.dto.MemorySessionMessageDTO;
import xiaozhi.modules.memory.entity.MemoryFactEntity;
import xiaozhi.modules.memory.entity.MemoryForgetTombstoneEntity;
import xiaozhi.modules.memory.service.impl.maintenance.MemorySpeakerConversationBuilder;

class MemoryGovernanceServiceTest {
    private final MemoryPolicyService policies = mock(MemoryPolicyService.class);
    private final MemoryForgetTombstoneDao tombstones = mock(MemoryForgetTombstoneDao.class);
    private final MemoryRevisionDao revisions = mock(MemoryRevisionDao.class);
    private final MemoryGovernanceService service = new MemoryGovernanceService(policies, tombstones, revisions);

    @Test
    void evidenceMustComeFromEligibleUserAndSamePerson() {
        var action = JSONUtil.parseObj("{\"source_role\":\"user\",\"evidence_quote\":\"我住杭州\"}");
        var user = message("user", "我住杭州", "alice");
        assertTrue(service.hasUserEvidence(action, List.of(user), "alice"));
        assertFalse(service.hasUserEvidence(action, List.of(user), "bob"));
        user.setRole("assistant");
        assertFalse(service.hasUserEvidence(action, List.of(user), "alice"));
        user.setRole("user");
        user.setMemoryEligible(false);
        assertFalse(service.hasUserEvidence(action, List.of(user), "alice"));
        user.setMemoryEligible(true);
        action.set("evidence_quote", "我住上海");
        assertFalse(service.hasUserEvidence(action, List.of(user), "alice"));
    }

    @Test
    void structuredAndEncodedIdentityCannotDisagree() {
        var message = message("user", "{\"content\":\"个人事实\",\"speaker_id\":\"bob\"}", "alice");
        assertThrows(IllegalArgumentException.class, () -> MemoryGovernanceService.speaker(message));
    }

    @Test
    void forgetBoundaryMatchesOnlyOwnerAndSelectedDevice() {
        var rule = new MemoryForgetTombstoneEntity();
        rule.setSpeakerId("alice");
        rule.setScopeId("device-a");
        rule.setPatternText("杭州");
        rule.setMatchType("contains");
        assertTrue(MemoryGovernanceService.matches(rule, "device-a", "alice", "我住杭州", true));
        assertFalse(MemoryGovernanceService.matches(rule, "device-a", "bob", "我住杭州", true));
        assertFalse(MemoryGovernanceService.matches(rule, "device-b", "alice", "我住杭州", true));
        assertTrue(MemoryGovernanceService.matches(rule, "device-b", "alice", "我住杭州", false));
    }

    @Test
    void retentionAndExpiredCapacityUseActualValidity() {
        var policy = new MemoryPolicyDTO();
        when(policies.get("agent")).thenReturn(policy);
        assertNull(service.expiresAt("agent"));
        policy.setRetentionDays(2);
        assertTrue(service.expiresAt("agent").after(new Date(System.currentTimeMillis() + 86_400_000L)));
        var fact = new MemoryFactEntity();
        fact.setStatus("active");
        fact.setCanonicalText("过期事实");
        fact.setExpiresAt(new Date(1));
        assertEquals(0, MemoryGovernanceService.activeChars(fact));
        fact.setExpiresAt(null);
        assertEquals(4, MemoryGovernanceService.activeChars(fact));
    }

    @Test
    void editingPrivateIdentityRequiresBoundSpeakerAndDevice() {
        var policy = new MemoryPolicyDTO();
        policy.setMemoryScope("DEVICE");
        when(policies.get("agent")).thenReturn(policy);
        var fact = new MemoryFactEntity();
        var dto = new MemoryFactDTO();
        dto.setVisibility("private");
        assertThrows(IllegalArgumentException.class, () -> service.editIdentity("agent", fact, dto));
        dto.setSpeakerId("alice");
        dto.setDeviceId("device-a");
        service.editIdentity("agent", fact, dto);
        verify(policies).requireSpeaker("agent", "alice");
        verify(policies, atLeastOnce()).requireDevice("agent", "device-a");
        assertEquals("alice", JSONUtil.parseObj(fact.getPayload()).getStr("speaker_id"));
    }

    @Test
    void dialogueRetainsCorrectionAfterOldEightThousandCharacterBoundary() {
        var builder = new MemorySpeakerConversationBuilder();
        var first = message("user", "甲".repeat(7900), "alice");
        var second = message("assistant", "乙".repeat(1000), null);
        var correction = message("user", "更正，我现在住苏州", "alice");
        var excluded = message("user", "会议隐私", "alice");
        excluded.setMemoryEligible(false);
        String conversation = builder.fromMessages(List.of(first, second, correction, excluded)).text();
        assertTrue(conversation.endsWith("更正，我现在住苏州\n"));
        assertFalse(conversation.contains("会议隐私"));
    }

    private static MemorySessionMessageDTO message(String role, String text, String speaker) {
        var message = new MemorySessionMessageDTO();
        message.setRole(role);
        message.setContent(text);
        message.setSpeakerId(speaker);
        return message;
    }
}

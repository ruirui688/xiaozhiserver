package xiaozhi.modules.memory.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import xiaozhi.modules.memory.dao.MemoryFactDao;
import xiaozhi.modules.memory.dao.MemoryForgetTombstoneDao;
import xiaozhi.modules.memory.entity.MemoryFactEntity;
import xiaozhi.modules.memory.entity.MemoryForgetTombstoneEntity;

class ManagedMemorySnapshotServiceTest {
    private final MemoryFactDao dao = mock(MemoryFactDao.class);
    private final MemoryForgetTombstoneDao tombstoneDao = mock(MemoryForgetTombstoneDao.class);
    private final ManagedMemorySnapshotService service = new ManagedMemorySnapshotService(dao, tombstoneDao);

    @Test
    void previewSelectsPrivateFirstDeduplicatesAndUsesEscapedCharacterBudget() {
        Map<String, Object> snapshot = Map.of("shared_context", "- duplicated\n- shared\n",
                "speaker_contexts", Map.of("alice", "- <&>\n- duplicated\n"),
                "context_max_chars", 29, "context_max_items", 3);
        String selected = ManagedMemorySnapshotService.selectedContext(snapshot, "alice");
        assertEquals("- <&>\n- duplicated", selected);
        assertTrue(ManagedMemorySnapshotService.escapedLength(selected) <= 29);
        assertEquals("- duplicated\n- shared", ManagedMemorySnapshotService.selectedContext(snapshot, "unknown"));
    }

    @Test
    void configuredPolicyUsesBoundedPerPersonSqlAndDeviceScope() {
        var policies = mock(MemoryPolicyService.class);
        var policy = new xiaozhi.modules.memory.dto.MemoryPolicyDTO();
        policy.setMemoryScope("DEVICE");
        when(policies.get("agent-a")).thenReturn(policy);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "policyService", policies);
        var fact = fact("private", "alice", "private fact");
        fact.setScopeId("device-a");
        when(dao.selectSnapshotCandidates("agent-a", "device-a", List.of("alice", ""), 100))
                .thenReturn(List.of(fact));
        var snapshot = service.build("agent-a", "device-a", List.of("alice"));
        assertEquals("- private fact", ManagedMemorySnapshotService.selectedContext(snapshot, "alice"));
        verify(dao).selectSnapshotCandidates("agent-a", "device-a", List.of("alice", ""), 100);
        verify(dao, never()).selectList(any());
    }

    private MemoryFactEntity fact(String id, String speaker, String text) {
        var fact = new MemoryFactEntity();
        fact.setMemoryId(id);
        fact.setAgentId("agent-a");
        fact.setStatus("active");
        fact.setCanonicalText(text);
        fact.setPayload(speaker == null ? null : "{\"speaker_id\":\"" + speaker + "\"}");
        return fact;
    }

    @Test
    @SuppressWarnings("unchecked")
    void loadsOneBoundedBatchAndSeparatesTrustedPrivateFacts() {
        var otherAgent = fact("other", null, "other agent private text");
        otherAgent.setAgentId("agent-b");
        var malformed = fact("malformed", null, "malformed attribution");
        malformed.setPayload("invalid JSON");
        when(dao.selectList(any())).thenReturn(List.of(fact("shared", null, "shared fact"),
                fact("private", "speaker-a", "private fact"), fact("unbound", "speaker-b", "unbound fact"),
                otherAgent, malformed));
        Map<String, Object> snapshot = service.build("agent-a", "device-a", List.of("speaker-a"));
        assertEquals("ready", snapshot.get("status"));
        assertEquals("- shared fact\n", snapshot.get("shared_context"));
        assertEquals(Map.of("speaker-a", "- private fact\n"), snapshot.get("speaker_contexts"));
        ArgumentCaptor<QueryWrapper<MemoryFactEntity>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(dao, times(1)).selectList(captor.capture());
        assertTrue(captor.getValue().getSqlSegment().contains("LIMIT 200"));
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue("agent-a"));
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue("speaker-a"));
        assertFalse(captor.getValue().getParamNameValuePairs().containsValue("speaker-b"));
    }

    @Test
    void omittedSpeakerCannotReceiveAnyPrivateFactAndExpiredOrDeletedFactsAreExcluded() {
        var expired = fact("expired", null, "expired");
        expired.setExpiresAt(new Date(1));
        var deleted = fact("deleted", null, "deleted");
        deleted.setStatus("deleted");
        when(dao.selectList(any())).thenReturn(List.of(fact("private", "speaker-a", "private"), expired, deleted));
        var snapshot = service.build("agent-a", "device-a", List.of());
        assertEquals("", snapshot.get("shared_context"));
        assertEquals(Map.of(), snapshot.get("speaker_contexts"));
    }

    @Test
    void revisionIsContentBasedAndLeaseDoesNotOutliveFacts() {
        var fact = fact("fact", null, "fact");
        long expiration = System.currentTimeMillis() + 10000;
        fact.setExpiresAt(new Date(expiration));
        when(dao.selectList(any())).thenReturn(List.of(fact));
        var first = service.build("agent-a", "device-a", List.of());
        var second = service.build("agent-a", "device-a", List.of());
        assertEquals(first.get("revision"), second.get("revision"));
        assertEquals(expiration, first.get("expires_at_ms"));
        fact.setCanonicalText("changed");
        assertNotEquals(first.get("revision"), service.build("agent-a", "device-a", List.of()).get("revision"));
    }

    @Test
    void enforcesAggregateAndSpeakerBudgetsWithoutPartialFactTruncation() {
        List<String> speakers = IntStream.range(0, 20).mapToObj(i -> "speaker-" + i).toList();
        when(dao.selectList(any())).thenReturn(IntStream.range(0, 20)
                .mapToObj(i -> fact("fact-" + i, "speaker-" + i, "x".repeat(1900))).toList());
        var snapshot = service.build("agent-a", "device-a", speakers);
        Map<?, ?> parts = (Map<?, ?>) snapshot.get("speaker_contexts");
        assertTrue(parts.size() <= 16);
        assertTrue(parts.values().stream().mapToInt(item -> item.toString().length()).sum() <= 16000);
        assertEquals(true, snapshot.get("truncated"));
        assertFalse(parts.containsKey("speaker-16"));
    }

    @Test
    void attachFailsClosedAndDoesNotQueryForOtherMemoryModes() {
        var config = new HashMap<String, Object>();
        config.put("selected_module", Map.of("Memory", "managed"));
        config.put("model_config", Map.of("managed", Map.of("typed_config", Map.of("type", "managed_memory"))));
        config.put("managed_memory_snapshot", Map.of("shared_context", "old sensitive value"));
        when(dao.selectList(any())).thenThrow(new IllegalStateException("private db error"));
        service.attach(config, "agent-a", "device-a");
        assertEquals("unavailable", ((Map<?, ?>) config.get("managed_memory_snapshot")).get("status"));
        assertFalse(config.get("managed_memory_snapshot").toString().contains("old sensitive value"));
        reset(dao);
        config.put("model_config", Map.of("managed", Map.of("typed_config", Map.of("type", "nomem"))));
        service.attach(config, "agent-a", "device-a");
        verifyNoInteractions(dao);
        assertFalse(config.containsKey("managed_memory_snapshot"));
    }

    @Test
    void invalidSpeakerTypesDoNotBecomeShared() {
        var facts = List.of("null", "123", "true", "{}", "[]").stream().map(value -> {
            var item = fact(value, null, "private identity must not leak");
            item.setPayload("{\"speaker_id\":" + value + "}");
            return item;
        }).toList();
        when(dao.selectList(any())).thenReturn(facts);
        var snapshot = service.build("agent-a", "device-a", List.of());
        assertEquals("", snapshot.get("shared_context"));
        assertEquals(Map.of(), snapshot.get("speaker_contexts"));
    }

    @Test
    void forgetPatternsFilterWithoutLeakingTextAndMoreThanFiftyRulesRemainAvailable() {
        var tombstone = new MemoryForgetTombstoneEntity();
        tombstone.setPatternText("secret address");
        when(tombstoneDao.selectList(any())).thenReturn(List.of(tombstone));
        when(dao.selectList(any())).thenReturn(List.of(fact("forgotten", null, "My SECRET Address is private"),
                fact("ok", null, "permitted")));
        var snapshot = service.build("agent-a", "device-a", List.of());
        assertEquals("- permitted\n", snapshot.get("shared_context"));
        assertFalse(snapshot.toString().contains("secret address"));
        when(tombstoneDao.selectList(any())).thenReturn(java.util.Collections.nCopies(51, tombstone));
        assertEquals("- permitted\n", service.build("agent-a", "device-a", List.of()).get("shared_context"));
    }
}

package xiaozhi.modules.memory.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import cn.hutool.json.JSONObject;
import xiaozhi.modules.memory.entity.MemoryFactEntity;
import xiaozhi.modules.memory.utils.MemorySpeakerUtils.Registry;
import xiaozhi.modules.memory.utils.MemorySpeakerUtils.SpeakerIdentity;

class MemorySpeakerUtilsTest {

    @Test
    void multiSpeakerRegistryRequiresTrustedExistingId() {
        Registry registry = new Registry();
        registry.observe(new SpeakerIdentity("voice-a", "用户A"));
        registry.observe(new SpeakerIdentity("voice-b", "用户B"));

        assertTrue(registry.requiresExplicitAttribution());
        assertNull(registry.resolve(new JSONObject()));
        assertNull(registry.resolve(new JSONObject().set("speaker_id", "invented")));
        assertEquals(new SpeakerIdentity("voice-b", "用户B"),
                registry.resolve(new JSONObject().set("speaker_id", "voice-b")));
    }

    @Test
    void singleSpeakerRegistryRejectsInventedIdButInfersBlankId() {
        Registry registry = new Registry();
        registry.observe(new SpeakerIdentity("voice-a", "用户A"));

        assertFalse(registry.requiresExplicitAttribution());
        assertNull(registry.resolve(new JSONObject().set("speaker_id", "invented")));
        assertEquals(new SpeakerIdentity("voice-a", "用户A"), registry.resolve(new JSONObject()));
    }

    @Test
    void speakerSpecificFactIsInvisibleToOtherOrUnknownSpeakers() {
        MemoryFactEntity fact = new MemoryFactEntity();
        fact.setPayload("{\"speaker_id\":\"voice-a\",\"speaker_name\":\"用户A\"}");

        assertTrue(MemorySpeakerUtils.isVisibleToSpeaker(fact, "voice-a"));
        assertFalse(MemorySpeakerUtils.isVisibleToSpeaker(fact, "voice-b"));
        assertFalse(MemorySpeakerUtils.isVisibleToSpeaker(fact, null));
    }
}

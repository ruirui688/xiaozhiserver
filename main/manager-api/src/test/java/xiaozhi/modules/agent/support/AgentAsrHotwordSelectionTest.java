package xiaozhi.modules.agent.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import xiaozhi.modules.agent.entity.AgentEntity;

class AgentAsrHotwordSelectionTest {

    @Test
    void normalizesCandidatesAndKeepsRequestedActiveId() {
        AgentEntity entity = new AgentEntity();

        AgentAsrHotwordSelection.apply(
                entity,
                " table-b ",
                List.of(" table-a ", "table-b", "table-a", " "));

        assertEquals("table-b", entity.getAsrHotwordId());
        assertEquals(List.of("table-a", "table-b"), entity.getAsrHotwordIds());
    }

    @Test
    void selectsFirstCandidateWhenPreviousActiveIdWasRemoved() {
        AgentEntity entity = new AgentEntity();
        entity.setAsrHotwordId("old-table");

        AgentAsrHotwordSelection.apply(entity, null, List.of("new-a", "new-b"));

        assertEquals("new-a", entity.getAsrHotwordId());
        assertEquals(List.of("new-a", "new-b"), entity.getAsrHotwordIds());
    }

    @Test
    void legacySingleIdUpdateAlsoAddsItToCandidates() {
        AgentEntity entity = new AgentEntity();
        entity.setAsrHotwordIds(List.of("table-a"));

        AgentAsrHotwordSelection.apply(entity, "table-b", null);

        assertEquals("table-b", entity.getAsrHotwordId());
        assertEquals(List.of("table-b", "table-a"), entity.getAsrHotwordIds());
    }

    @Test
    void clearingBothFieldsDisablesHotwords() {
        AgentEntity entity = new AgentEntity();
        entity.setAsrHotwordId("table-a");
        entity.setAsrHotwordIds(List.of("table-a"));

        AgentAsrHotwordSelection.apply(entity, "", new ArrayList<>());

        assertNull(entity.getAsrHotwordId());
        assertNull(entity.getAsrHotwordIds());
    }
}

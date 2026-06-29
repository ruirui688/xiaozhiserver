package xiaozhi.modules.memory.vo;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;
import xiaozhi.modules.memory.entity.MemoryCandidateEntity;
import xiaozhi.modules.memory.entity.MemoryFactEntity;
import xiaozhi.modules.memory.entity.MemoryForgetTombstoneEntity;
import xiaozhi.modules.memory.entity.MemoryProfileEntity;
import xiaozhi.modules.memory.entity.MemoryRevisionEntity;
import xiaozhi.modules.memory.entity.MemoryToolBehaviorEntity;

@Data
public class MemoryDashboardVO {
    private MemoryProfileEntity profile;
    private List<MemoryFactEntity> facts = new ArrayList<>();
    private List<MemoryCandidateEntity> candidates = new ArrayList<>();
    private List<MemoryToolBehaviorEntity> toolBehaviors = new ArrayList<>();
    private List<MemoryForgetTombstoneEntity> tombstones = new ArrayList<>();
    private List<MemoryRevisionEntity> revisions = new ArrayList<>();
}

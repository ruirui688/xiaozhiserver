package xiaozhi.modules.memory.vo;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;
import xiaozhi.modules.memory.entity.MemoryFactEntity;
import xiaozhi.modules.memory.entity.MemoryToolBehaviorEntity;

@Data
public class MemoryQueryVO {
    private String profile;
    private List<MemoryFactEntity> facts = new ArrayList<>();
    private List<MemoryToolBehaviorEntity> toolBehaviors = new ArrayList<>();
    private List<String> blockedPatterns = new ArrayList<>();
    private String context;
}

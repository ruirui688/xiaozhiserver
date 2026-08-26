package xiaozhi.modules.person.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PersonAgentOptionVO {
    private String id;
    private String name;
    private long deviceCount;
}

package xiaozhi.modules.maintenance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class HarnessConversationMessage {
    private String role;
    private String content;
}

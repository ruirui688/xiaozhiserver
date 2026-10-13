package xiaozhi.modules.person.vo;

import java.util.Date;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PersonProfileVO {
    private String id;
    private String name;
    private String gender;
    @JsonFormat(pattern = "yyyy-MM-dd", timezone = "Asia/Shanghai")
    private Date birthday;
    private String groupName;
    private String description;
    private boolean voicePrintReady;
    private String voicePrintId;
    private String voicePrintAgentId;
    private String voicePrintSourceName;
    private String voicePrintDescription;
    private Date voicePrintCreateDate;
    private boolean faceReady;
    private Long faceVersion;
    private String faceSha256;
    private String recognitionAction;
    private String greetingText;
    private Integer status;
    private List<String> deviceIds;
    private List<String> deviceNames;
    private List<String> agentIds;
    private List<String> agentNames;
    private List<String> publishedAgentIds;
    private boolean unpublishedChanges;
    private Date createDate;
    private Date updateDate;
}

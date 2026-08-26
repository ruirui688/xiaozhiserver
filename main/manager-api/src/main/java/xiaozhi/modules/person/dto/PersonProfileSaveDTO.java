package xiaozhi.modules.person.dto;

import java.util.Date;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PersonProfileSaveDTO {
    @NotBlank(message = "姓名不能为空")
    @Size(max = 30, message = "姓名不能超过30个字符")
    private String name;

    @Pattern(regexp = "MALE|FEMALE|UNKNOWN", message = "性别值不支持")
    private String gender;

    @JsonFormat(pattern = "yyyy-MM-dd", timezone = "Asia/Shanghai")
    private Date birthday;

    @Size(max = 60, message = "分组不能超过60个字符")
    private String groupName;

    @Size(max = 200, message = "介绍不能超过200个字符")
    private String description;

    @Pattern(regexp = "NONE|SPEAK_TEXT", message = "识别后动作不支持")
    private String recognitionAction;

    @Size(max = 500, message = "播报内容不能超过500个字符")
    private String greetingText;

    private Integer status;

    @Size(max = 50, message = "单个人物最多下发到50台设备")
    @Deprecated
    private List<String> deviceIds;

    @Size(max = 50, message = "单个人物最多绑定50个智能体")
    private List<String> agentIds;
}

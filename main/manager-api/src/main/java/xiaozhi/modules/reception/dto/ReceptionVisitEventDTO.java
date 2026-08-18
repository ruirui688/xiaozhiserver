package xiaozhi.modules.reception.dto;

import java.util.Date;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ReceptionVisitEventDTO {
    @NotBlank
    private String macAddress;
    @NotBlank
    @Size(max = 128)
    private String eventId;
    @NotBlank
    @Size(max = 128)
    private String groupSessionId;
    private Long sceneEpoch;
    @NotEmpty
    @Size(max = 3)
    private List<@NotBlank String> selectedPersonIds;
    @Min(1)
    @Max(8)
    private Integer presentCount;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    private Date capturedAt;
}

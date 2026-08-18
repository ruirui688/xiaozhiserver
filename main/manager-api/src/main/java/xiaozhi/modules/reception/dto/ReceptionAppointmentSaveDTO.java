package xiaozhi.modules.reception.dto;

import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ReceptionAppointmentSaveDTO {
    @NotNull
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private Date startAt;
    @NotNull
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private Date endAt;
    @Min(1)
    @Max(20)
    private Integer partySize;
    @Pattern(regexp = "PENDING|CONFIRMED|ARRIVED|CANCELLED|COMPLETED|NO_SHOW")
    private String status;
    @Size(max = 200)
    private String seatRequest;
    @Size(max = 500)
    private String notes;
}

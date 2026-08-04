package xiaozhi.modules.person.dto;

import java.math.BigDecimal;
import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class DeviceFaceRecognitionEventDTO {
    @NotBlank
    @Size(max = 64)
    private String macAddress;

    @NotBlank
    @Size(max = 128)
    private String eventId;

    @NotBlank
    @Size(max = 32)
    private String personId;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private BigDecimal confidence;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    private Date occurredAt;
}

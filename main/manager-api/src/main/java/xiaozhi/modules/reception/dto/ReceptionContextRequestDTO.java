package xiaozhi.modules.reception.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ReceptionContextRequestDTO {
    @NotBlank
    private String macAddress;

    @NotEmpty
    @Size(max = 8)
    private List<@NotBlank String> personIds;
}

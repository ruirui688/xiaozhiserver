package xiaozhi.modules.music.dto;

import java.util.List;

import lombok.Data;

@Data
public class MusicLibraryDTO {
    private String musicDir;
    private Integer total;
    private List<MusicFileDTO> files;
}

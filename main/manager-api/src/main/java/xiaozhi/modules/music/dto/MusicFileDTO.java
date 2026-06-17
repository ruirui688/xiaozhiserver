package xiaozhi.modules.music.dto;

import lombok.Data;

@Data
public class MusicFileDTO {
    private String name;
    private String path;
    private String extension;
    private Long size;
    private Long updateTime;
    private String playUrl;
    private String description;
}

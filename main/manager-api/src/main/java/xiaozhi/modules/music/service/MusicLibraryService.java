package xiaozhi.modules.music.service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.springframework.web.multipart.MultipartFile;

import xiaozhi.modules.music.dto.MusicFileDTO;
import xiaozhi.modules.music.dto.MusicLibraryDTO;

public interface MusicLibraryService {
    MusicLibraryDTO list(String keyword);

    List<MusicFileDTO> upload(MultipartFile[] files) throws IOException;

    MusicFileDTO rename(String path, String newName) throws IOException;

    void delete(String path) throws IOException;

    Path resolveExistingFile(String path) throws IOException;

    String getContentType(Path path);
}

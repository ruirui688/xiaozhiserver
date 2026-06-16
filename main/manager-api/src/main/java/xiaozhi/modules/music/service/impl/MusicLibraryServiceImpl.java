package xiaozhi.modules.music.service.impl;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import xiaozhi.modules.music.dto.MusicFileDTO;
import xiaozhi.modules.music.dto.MusicLibraryDTO;
import xiaozhi.modules.music.service.MusicLibraryService;

@Service
public class MusicLibraryServiceImpl implements MusicLibraryService {
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".mp3", ".wav", ".p3");

    private final Path musicRoot;

    public MusicLibraryServiceImpl(@Value("${xiaozhi.music.dir:}") String configuredMusicDir) {
        this.musicRoot = resolveMusicRoot(configuredMusicDir);
    }

    @Override
    public MusicLibraryDTO list(String keyword) {
        ensureMusicRoot();

        String normalizedKeyword = StringUtils.defaultString(keyword).trim().toLowerCase(Locale.ROOT);
        List<MusicFileDTO> files = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(musicRoot)) {
            paths.filter(Files::isRegularFile)
                    .filter(this::isAllowedAudioFile)
                    .map(this::toDTO)
                    .filter(item -> StringUtils.isBlank(normalizedKeyword)
                            || item.getName().toLowerCase(Locale.ROOT).contains(normalizedKeyword)
                            || item.getPath().toLowerCase(Locale.ROOT).contains(normalizedKeyword))
                    .sorted(Comparator.comparing(MusicFileDTO::getUpdateTime).reversed())
                    .forEach(files::add);
        } catch (IOException e) {
            throw new IllegalStateException("读取音乐目录失败：" + e.getMessage(), e);
        }

        MusicLibraryDTO dto = new MusicLibraryDTO();
        dto.setMusicDir(musicRoot.toString());
        dto.setTotal(files.size());
        dto.setFiles(files);
        return dto;
    }

    @Override
    public List<MusicFileDTO> upload(MultipartFile[] files) throws IOException {
        ensureMusicRoot();
        if (files == null || files.length == 0) {
            throw new IllegalArgumentException("请选择要上传的音乐文件");
        }

        List<MusicFileDTO> savedFiles = new ArrayList<>();
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            String originalName = sanitizeFilename(file.getOriginalFilename());
            String extension = getExtension(originalName);
            if (!ALLOWED_EXTENSIONS.contains(extension)) {
                throw new IllegalArgumentException("只允许上传 mp3、wav、p3 格式的音频文件");
            }

            Path target = uniqueTargetPath(originalName);
            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
            }
            savedFiles.add(toDTO(target));
        }

        if (savedFiles.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的音乐文件");
        }
        return savedFiles;
    }

    @Override
    public MusicFileDTO rename(String path, String newName) throws IOException {
        Path source = resolveExistingFile(path);
        String cleanName = sanitizeFilename(newName);
        String extension = getExtension(cleanName);
        if (StringUtils.isBlank(extension)) {
            extension = getExtension(source.getFileName().toString());
            cleanName = cleanName + extension;
        }
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("只允许使用 mp3、wav、p3 格式的音频文件名");
        }

        Path parent = source.getParent();
        Path target = parent.resolve(cleanName).normalize();
        if (!target.startsWith(musicRoot)) {
            throw new IllegalArgumentException("文件名不合法");
        }
        if (source.equals(target)) {
            return toDTO(source);
        }
        if (Files.exists(target)) {
            throw new IllegalArgumentException("同名音乐文件已存在");
        }

        Files.move(source, target);
        return toDTO(target);
    }

    @Override
    public void delete(String path) throws IOException {
        Path target = resolveExistingFile(path);
        Files.delete(target);
    }

    @Override
    public Path resolveExistingFile(String path) throws IOException {
        Path target = resolveUserPath(path);
        if (!Files.isRegularFile(target) || !isAllowedAudioFile(target)) {
            throw new IOException("音乐文件不存在");
        }
        return target;
    }

    @Override
    public String getContentType(Path path) {
        String extension = getExtension(path.getFileName().toString());
        if (".mp3".equals(extension)) {
            return "audio/mpeg";
        }
        if (".wav".equals(extension)) {
            return "audio/wav";
        }
        return "application/octet-stream";
    }

    private Path resolveUserPath(String path) {
        if (StringUtils.isBlank(path)) {
            throw new IllegalArgumentException("文件路径不能为空");
        }
        Path target = musicRoot.resolve(path).normalize();
        if (!target.startsWith(musicRoot)) {
            throw new IllegalArgumentException("文件路径不合法");
        }
        return target;
    }

    private MusicFileDTO toDTO(Path file) {
        try {
            Path relativePath = musicRoot.relativize(file);
            String normalizedPath = relativePath.toString().replace('\\', '/');
            MusicFileDTO dto = new MusicFileDTO();
            dto.setName(file.getFileName().toString());
            dto.setPath(normalizedPath);
            dto.setExtension(getExtension(file.getFileName().toString()));
            dto.setSize(Files.size(file));
            dto.setUpdateTime(Files.getLastModifiedTime(file).toMillis());
            dto.setPlayUrl("/admin/music/play?path=" + URLEncoder.encode(normalizedPath, StandardCharsets.UTF_8));
            return dto;
        } catch (IOException e) {
            throw new IllegalStateException("读取音乐文件信息失败：" + e.getMessage(), e);
        }
    }

    private boolean isAllowedAudioFile(Path path) {
        return ALLOWED_EXTENSIONS.contains(getExtension(path.getFileName().toString()));
    }

    private Path uniqueTargetPath(String filename) {
        String extension = getExtension(filename);
        String baseName = filename.substring(0, filename.length() - extension.length());
        Path target = musicRoot.resolve(filename).normalize();
        int index = 1;
        while (Files.exists(target)) {
            target = musicRoot.resolve(baseName + "(" + index + ")" + extension).normalize();
            index++;
        }
        return target;
    }

    private String sanitizeFilename(String filename) {
        String cleanName = StringUtils.defaultIfBlank(filename, "music.mp3").replace('\\', '/');
        int slashIndex = cleanName.lastIndexOf('/');
        if (slashIndex >= 0) {
            cleanName = cleanName.substring(slashIndex + 1);
        }
        cleanName = cleanName.replaceAll("[\\x00-\\x1F]", "").trim();
        if (StringUtils.isBlank(cleanName) || ".".equals(cleanName) || "..".equals(cleanName)) {
            cleanName = "music.mp3";
        }
        return cleanName;
    }

    private String getExtension(String filename) {
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex < 0) {
            return "";
        }
        return filename.substring(dotIndex).toLowerCase(Locale.ROOT);
    }

    private void ensureMusicRoot() {
        try {
            Files.createDirectories(musicRoot);
        } catch (IOException e) {
            throw new IllegalStateException("创建音乐目录失败：" + e.getMessage(), e);
        }
    }

    private Path resolveMusicRoot(String configuredMusicDir) {
        String envMusicDir = System.getenv("XIAOZHI_MUSIC_DIR");
        String explicitDir = StringUtils.firstNonBlank(configuredMusicDir, envMusicDir, System.getProperty("xiaozhi.music.dir"));
        if (StringUtils.isNotBlank(explicitDir)) {
            return Paths.get(explicitDir).toAbsolutePath().normalize();
        }

        Path cwd = Paths.get("").toAbsolutePath().normalize();
        if (cwd.getFileName() != null && "manager-api".equals(cwd.getFileName().toString())) {
            return cwd.resolve("../xiaozhi-server/music").normalize();
        }
        if (Files.exists(cwd.resolve("main/xiaozhi-server"))) {
            return cwd.resolve("main/xiaozhi-server/music").normalize();
        }
        if (Files.exists(cwd.resolve("xiaozhi-server"))) {
            return cwd.resolve("xiaozhi-server/music").normalize();
        }
        return cwd.resolve("music").normalize();
    }
}

package xiaozhi.modules.music.service.impl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import xiaozhi.modules.music.dto.MusicFileDTO;
import xiaozhi.modules.music.dto.MusicLibraryDTO;
import xiaozhi.modules.music.service.MusicLibraryService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class MusicLibraryServiceImpl implements MusicLibraryService {
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".mp3", ".wav", ".p3");
    private static final Set<String> OPTIMIZABLE_EXTENSIONS = Set.of(".mp3", ".wav");
    private static final String CACHE_DIR_NAME = ".xiaozhi-music-cache";
    private static final String METADATA_FILE_NAME = ".xiaozhi-music-metadata.json";
    private static final String OPTIMIZED_EXTENSION = ".p3";
    private static final long OPTIMIZE_TIMEOUT_MINUTES = 5;
    private static final int MAX_DESCRIPTION_LENGTH = 500;

    private final Path musicRoot;
    private final Path musicCacheRoot;
    private final Path metadataPath;
    private final boolean optimizeEnabled;
    private final int optimizedSampleRate;
    private final int optimizedBitrate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MusicLibraryServiceImpl(@Value("${xiaozhi.music.dir:}") String configuredMusicDir,
                                   @Value("${xiaozhi.music.optimize.enabled:true}") boolean optimizeEnabled,
                                   @Value("${xiaozhi.music.optimize.sample-rate:24000}") int optimizedSampleRate,
                                   @Value("${xiaozhi.music.optimize.bitrate:48000}") int optimizedBitrate) {
        this.musicRoot = resolveMusicRoot(configuredMusicDir);
        this.musicCacheRoot = this.musicRoot.resolve(CACHE_DIR_NAME).normalize();
        this.metadataPath = this.musicRoot.resolve(METADATA_FILE_NAME).normalize();
        this.optimizeEnabled = optimizeEnabled;
        this.optimizedSampleRate = optimizedSampleRate;
        this.optimizedBitrate = optimizedBitrate;
    }

    @Override
    public MusicLibraryDTO list(String keyword) {
        ensureMusicRoot();

        String normalizedKeyword = StringUtils.defaultString(keyword).trim().toLowerCase(Locale.ROOT);
        List<MusicFileDTO> files = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(musicRoot)) {
            paths.filter(Files::isRegularFile)
                    .filter(this::isVisibleAudioFile)
                    .map(this::toDTO)
                    .filter(item -> StringUtils.isBlank(normalizedKeyword)
                            || item.getName().toLowerCase(Locale.ROOT).contains(normalizedKeyword)
                            || item.getPath().toLowerCase(Locale.ROOT).contains(normalizedKeyword)
                            || StringUtils.defaultString(item.getDescription()).toLowerCase(Locale.ROOT)
                                    .contains(normalizedKeyword))
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
            prepareOptimizedAudio(target);
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
        String sourceExtension = getExtension(source.getFileName().toString());
        if (!sourceExtension.equals(extension)) {
            throw new IllegalArgumentException("重命名不能修改音频格式");
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

        String oldRelativePath = normalizeRelativePath(source);
        String newRelativePath = normalizeRelativePath(target);
        Path sourceOptimized = optimizedPathFor(source);
        Files.move(source, target);
        moveOptimizedAudio(sourceOptimized, optimizedPathFor(target));
        moveMetadata(oldRelativePath, newRelativePath);
        return toDTO(target);
    }

    @Override
    public MusicFileDTO updateDescription(String path, String description) throws IOException {
        Path target = resolveExistingFile(path);
        String normalizedPath = normalizeRelativePath(target);
        String cleanDescription = normalizeDescription(description);
        Map<String, MusicMetadata> metadata = readMetadata();
        if (StringUtils.isBlank(cleanDescription)) {
            metadata.remove(normalizedPath);
        } else {
            MusicMetadata item = metadata.getOrDefault(normalizedPath, new MusicMetadata());
            item.setDescription(cleanDescription);
            metadata.put(normalizedPath, item);
        }
        writeMetadata(metadata);
        return toDTO(target);
    }

    @Override
    public void delete(String path) throws IOException {
        Path target = resolveExistingFile(path);
        String normalizedPath = normalizeRelativePath(target);
        Files.delete(target);
        deleteOptimizedAudio(target);
        deleteMetadata(normalizedPath);
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
            String normalizedPath = normalizeRelativePath(file);
            MusicFileDTO dto = new MusicFileDTO();
            dto.setName(file.getFileName().toString());
            dto.setPath(normalizedPath);
            dto.setExtension(getExtension(file.getFileName().toString()));
            dto.setSize(Files.size(file));
            dto.setUpdateTime(Files.getLastModifiedTime(file).toMillis());
            dto.setPlayUrl("/admin/music/play?path=" + URLEncoder.encode(normalizedPath, StandardCharsets.UTF_8));
            dto.setDescription(getDescription(normalizedPath));
            return dto;
        } catch (IOException e) {
            throw new IllegalStateException("读取音乐文件信息失败：" + e.getMessage(), e);
        }
    }

    private String normalizeRelativePath(Path file) {
        Path relativePath = musicRoot.relativize(file.normalize());
        return relativePath.toString().replace('\\', '/');
    }

    private String getDescription(String normalizedPath) {
        MusicMetadata metadata = readMetadata().get(normalizedPath);
        return metadata == null ? "" : StringUtils.defaultString(metadata.getDescription());
    }

    private String normalizeDescription(String description) {
        String cleanDescription = StringUtils.defaultString(description)
                .replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "")
                .trim();
        if (cleanDescription.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException("音乐提示词最多 " + MAX_DESCRIPTION_LENGTH + " 个字符");
        }
        return cleanDescription;
    }

    private Map<String, MusicMetadata> readMetadata() {
        if (!Files.isRegularFile(metadataPath)) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, MusicMetadata> metadata = objectMapper.readValue(
                    metadataPath.toFile(),
                    new TypeReference<LinkedHashMap<String, MusicMetadata>>() {});
            return metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
        } catch (Exception e) {
            log.warn("读取音乐提示词元数据失败，将按空元数据处理：path={}, error={}", metadataPath, e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private void writeMetadata(Map<String, MusicMetadata> metadata) throws IOException {
        ensureMusicRoot();
        Map<String, MusicMetadata> cleanedMetadata = new LinkedHashMap<>();
        for (Map.Entry<String, MusicMetadata> entry : metadata.entrySet()) {
            String path = StringUtils.defaultString(entry.getKey()).replace('\\', '/').trim();
            MusicMetadata value = entry.getValue();
            String description = value == null ? "" : normalizeDescription(value.getDescription());
            if (StringUtils.isNotBlank(path) && StringUtils.isNotBlank(description)) {
                MusicMetadata item = new MusicMetadata();
                item.setDescription(description);
                cleanedMetadata.put(path, item);
            }
        }
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(metadataPath.toFile(), cleanedMetadata);
    }

    private void moveMetadata(String oldPath, String newPath) throws IOException {
        Map<String, MusicMetadata> metadata = readMetadata();
        MusicMetadata item = metadata.remove(oldPath);
        if (item == null) {
            return;
        }
        metadata.put(newPath, item);
        writeMetadata(metadata);
    }

    private void deleteMetadata(String path) throws IOException {
        Map<String, MusicMetadata> metadata = readMetadata();
        if (metadata.remove(path) != null) {
            writeMetadata(metadata);
        }
    }

    private boolean isAllowedAudioFile(Path path) {
        return ALLOWED_EXTENSIONS.contains(getExtension(path.getFileName().toString()));
    }

    private boolean isVisibleAudioFile(Path path) {
        return isAllowedAudioFile(path) && !path.normalize().startsWith(musicCacheRoot);
    }

    private void prepareOptimizedAudio(Path source) {
        if (!optimizeEnabled || !isOptimizableAudioFile(source)) {
            return;
        }

        Path optimizedPath = optimizedPathFor(source);
        try {
            if (Files.exists(optimizedPath)
                    && Files.getLastModifiedTime(optimizedPath).toMillis() >= Files.getLastModifiedTime(source).toMillis()) {
                return;
            }
            Files.createDirectories(optimizedPath.getParent());
            runOptimizeCommand(source, optimizedPath);
        } catch (Exception e) {
            log.warn("生成音乐低延时缓存失败，将播放原始音频：source={}, target={}, error={}",
                    source, optimizedPath, e.getMessage());
            try {
                Files.deleteIfExists(optimizedPath);
            } catch (IOException deleteError) {
                log.warn("删除失败的音乐缓存失败：{}", optimizedPath, deleteError);
            }
        }
    }

    private void runOptimizeCommand(Path source, Path optimizedPath) throws IOException, InterruptedException {
        Path script = resolveEncoderScript();
        List<List<String>> pythonCommands = resolvePythonCommands();
        IOException lastIoException = null;
        InterruptedException lastInterruptedException = null;
        String lastOutput = "";

        for (List<String> pythonCommand : pythonCommands) {
            List<String> command = new ArrayList<>(pythonCommand);
            command.add(script.toString());
            command.add(source.toString());
            command.add(optimizedPath.toString());
            command.add("--sample-rate");
            command.add(String.valueOf(optimizedSampleRate));
            command.add("--bitrate");
            command.add(String.valueOf(optimizedBitrate));

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            try {
                Process process = builder.start();
                boolean finished = process.waitFor(OPTIMIZE_TIMEOUT_MINUTES, TimeUnit.MINUTES);
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                if (!finished) {
                    process.destroyForcibly();
                    lastOutput = "转换超时";
                    continue;
                }
                if (process.exitValue() == 0 && Files.isRegularFile(optimizedPath)) {
                    log.info("音乐低延时缓存生成成功：{} -> {}", source, optimizedPath);
                    return;
                }
                lastOutput = StringUtils.defaultIfBlank(output, "exit=" + process.exitValue());
            } catch (IOException e) {
                lastIoException = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                lastInterruptedException = e;
                break;
            }
        }

        if (lastInterruptedException != null) {
            throw lastInterruptedException;
        }
        if (lastIoException != null && StringUtils.isBlank(lastOutput)) {
            throw lastIoException;
        }
        throw new IOException("转换命令执行失败：" + lastOutput);
    }

    private List<List<String>> resolvePythonCommands() {
        List<List<String>> commands = new ArrayList<>();

        String configuredPython = StringUtils.firstNonBlank(
                System.getProperty("xiaozhi.music.encoder.python"),
                System.getenv("XIAOZHI_MUSIC_ENCODER_PYTHON"));
        if (StringUtils.isNotBlank(configuredPython)) {
            commands.add(List.of(configuredPython));
        }

        String condaPrefix = System.getenv("CONDA_PREFIX");
        if (StringUtils.isNotBlank(condaPrefix)) {
            commands.add(List.of(Paths.get(condaPrefix, "bin", "python").toString()));
        }

        String home = System.getProperty("user.home");
        if (StringUtils.isNotBlank(home)) {
            commands.add(List.of(Paths.get(home, "miniconda3", "envs", "xiaozhi-esp32-server", "bin", "python").toString()));
        }

        commands.add(List.of("conda", "run", "-n", "xiaozhi-esp32-server", "python"));
        commands.add(List.of("python3"));
        commands.add(List.of("python"));
        return commands;
    }

    private Path resolveEncoderScript() {
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        List<Path> candidates = List.of(
                cwd.resolve("../xiaozhi-server/scripts/audio_to_p3.py").normalize(),
                cwd.resolve("main/xiaozhi-server/scripts/audio_to_p3.py").normalize(),
                cwd.resolve("xiaozhi-server/scripts/audio_to_p3.py").normalize(),
                musicRoot.getParent() == null ? cwd.resolve("scripts/audio_to_p3.py").normalize()
                        : musicRoot.getParent().resolve("scripts/audio_to_p3.py").normalize());
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("未找到音乐转换脚本 audio_to_p3.py");
    }

    private boolean isOptimizableAudioFile(Path path) {
        return OPTIMIZABLE_EXTENSIONS.contains(getExtension(path.getFileName().toString()))
                && !path.normalize().startsWith(musicCacheRoot);
    }

    private Path optimizedPathFor(Path source) {
        Path relativePath = musicRoot.relativize(source.normalize());
        return musicCacheRoot.resolve(relativePath.toString().replace('\\', '/') + OPTIMIZED_EXTENSION).normalize();
    }

    private void moveOptimizedAudio(Path sourceOptimized, Path targetOptimized) throws IOException {
        if (sourceOptimized == null || targetOptimized == null || !Files.exists(sourceOptimized)) {
            return;
        }
        Files.createDirectories(targetOptimized.getParent());
        Files.move(sourceOptimized, targetOptimized);
    }

    private void deleteOptimizedAudio(Path source) throws IOException {
        Path optimizedPath = optimizedPathFor(source);
        Files.deleteIfExists(optimizedPath);
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

    @Data
    private static class MusicMetadata {
        private String description;
    }
}

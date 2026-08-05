package xiaozhi.modules.person.service;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import jakarta.annotation.PostConstruct;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import xiaozhi.common.exception.RenException;

@Component
public class PersonFaceStorage {
    private static final long MAX_UPLOAD_BYTES = 5L * 1024 * 1024;
    private static final long MAX_IMAGE_PIXELS = 16_000_000L;
    private static final int MAX_EDGE = 640;

    private final Path storageRoot;

    public PersonFaceStorage(@Value("${person.face-storage-root:uploadfile/person-faces}") String configuredRoot) {
        Path configured = Paths.get(configuredRoot);
        this.storageRoot = (configured.isAbsolute()
                ? configured
                : Paths.get(System.getProperty("user.dir")).resolve(configured))
                .toAbsolutePath().normalize();
    }

    @PostConstruct
    void ensureStorageReady() {
        try {
            Files.createDirectories(storageRoot);
            if (!Files.isDirectory(storageRoot) || !Files.isWritable(storageRoot)) {
                throw new IllegalStateException("人物人脸存储目录不可写: " + storageRoot);
            }
        } catch (IOException e) {
            throw new IllegalStateException("无法初始化人物人脸存储目录: " + storageRoot, e);
        }
    }

    public StoredFace store(String workspaceId, String personId, MultipartFile file) {
        validateIdentifier(workspaceId);
        validateIdentifier(personId);
        if (file == null || file.isEmpty()) {
            throw new RenException("请选择人脸照片");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new RenException("人脸照片不能超过5MB");
        }
        String originalName = StringUtils.defaultString(file.getOriginalFilename()).toLowerCase(Locale.ROOT);
        if (!(originalName.endsWith(".jpg") || originalName.endsWith(".jpeg") || originalName.endsWith(".png"))) {
            throw new RenException("仅支持 JPG、JPEG、PNG 图片");
        }
        String declaredType = StringUtils.defaultString(file.getContentType()).toLowerCase(Locale.ROOT);
        if (!("image/jpeg".equals(declaredType) || "image/png".equals(declaredType))) {
            throw new RenException("图片 MIME 类型不支持");
        }

        try (InputStream input = file.getInputStream()) {
            BufferedImage source = ImageIO.read(input);
            if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0) {
                throw new RenException("无法解析图片内容");
            }
            if ((long) source.getWidth() * source.getHeight() > MAX_IMAGE_PIXELS) {
                throw new RenException("图片分辨率过大");
            }
            BufferedImage normalized = normalize(source);
            byte[] jpeg = encodeJpeg(normalized);
            String relative = workspaceId + "/" + personId + "/" + UUID.randomUUID().toString().replace("-", "") + ".jpg";
            Path target = resolve(relative);
            Files.createDirectories(target.getParent());
            Files.write(target, jpeg);
            return new StoredFace(relative, "image/jpeg", sha256(jpeg));
        } catch (RenException e) {
            throw e;
        } catch (IOException e) {
            throw new RenException("保存人脸照片失败", e);
        }
    }

    public byte[] read(String relativePath) {
        if (StringUtils.isBlank(relativePath)) {
            throw new RenException("人物尚未上传人脸照片");
        }
        try {
            Path file = resolve(relativePath);
            Path realRoot = Files.createDirectories(storageRoot).toRealPath();
            Path realFile = file.toRealPath();
            if (!realFile.startsWith(realRoot) || !Files.isRegularFile(realFile)) {
                throw new RenException("人脸照片不存在");
            }
            return Files.readAllBytes(realFile);
        } catch (RenException e) {
            throw e;
        } catch (IOException e) {
            throw new RenException("读取人脸照片失败", e);
        }
    }

    public void deleteQuietly(String relativePath) {
        if (StringUtils.isBlank(relativePath)) {
            return;
        }
        try {
            Files.deleteIfExists(resolve(relativePath));
        } catch (IOException ignored) {
            // 数据库记录已经移除时，残留文件可由后续运维任务清理。
        }
    }

    private Path resolve(String relativePath) {
        Path resolved = storageRoot.resolve(relativePath).toAbsolutePath().normalize();
        if (!resolved.startsWith(storageRoot)) {
            throw new RenException("非法人脸文件路径");
        }
        return resolved;
    }

    private void validateIdentifier(String value) {
        if (StringUtils.isBlank(value) || !value.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new RenException("非法资源标识");
        }
    }

    private BufferedImage normalize(BufferedImage source) {
        double scale = Math.min(1.0, (double) MAX_EDGE / Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private byte[] encodeJpeg(BufferedImage image) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("JPEG encoder unavailable");
        }
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
                ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(imageOutput);
            ImageWriteParam params = writer.getDefaultWriteParam();
            if (params.canWriteCompressed()) {
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality(0.86f);
            }
            writer.write(null, new IIOImage(image, null, null), params);
            return output.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record StoredFace(String relativePath, String contentType, String sha256) {
    }
}

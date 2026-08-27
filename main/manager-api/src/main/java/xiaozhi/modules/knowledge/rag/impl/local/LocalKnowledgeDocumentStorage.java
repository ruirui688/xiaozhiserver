package xiaozhi.modules.knowledge.rag.impl.local;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Locale;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import xiaozhi.common.exception.RenException;

/** 持久化本地知识库原始文档。目录中的文件是事实来源，不是数据库副本。 */
@Component
public class LocalKnowledgeDocumentStorage {
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private final Path root;

    public LocalKnowledgeDocumentStorage(
            @Value("${knowledge.document-storage-root:uploadfile/knowledge-documents}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    LocalKnowledgeDocumentStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path store(MultipartFile source, String datasetId, String documentId, String filename) {
        Path directory = documentDirectory(datasetId, documentId);
        String extension = safeExtension(filename);
        Path target = resolveInside(directory, "source" + extension);
        Path temporary = resolveInside(directory, ".source-upload.tmp");
        try {
            Files.createDirectories(directory);
            try (InputStream input = source.getInputStream()) {
                Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        } catch (IOException e) {
            deleteQuietly(temporary);
            throw new RenException("保存知识库原始文档失败");
        }
    }

    public void deleteDocument(String datasetId, String documentId) {
        deleteTree(documentDirectory(datasetId, documentId));
    }

    public void deleteDataset(String datasetId) {
        deleteTree(datasetDirectory(datasetId));
    }

    Path sourcePath(String datasetId, String documentId, String filename) {
        return resolveInside(documentDirectory(datasetId, documentId), "source" + safeExtension(filename));
    }

    private Path datasetDirectory(String datasetId) {
        requireSafeId(datasetId, "知识库ID");
        return resolveInside(root, datasetId);
    }

    private Path documentDirectory(String datasetId, String documentId) {
        requireSafeId(documentId, "文档ID");
        return resolveInside(datasetDirectory(datasetId), documentId);
    }

    private Path resolveInside(Path parent, String child) {
        Path resolved = parent.resolve(child).normalize();
        if (!resolved.startsWith(root)) {
            throw new RenException("知识库文档路径不合法");
        }
        return resolved;
    }

    private void requireSafeId(String value, String label) {
        if (StringUtils.isBlank(value) || !SAFE_ID.matcher(value).matches()) {
            throw new RenException(label + "不合法");
        }
    }

    private String safeExtension(String filename) {
        String name = StringUtils.defaultString(filename).replace('\\', '/');
        name = StringUtils.substringAfterLast(name, "/");
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return ".bin";
        }
        String extension = name.substring(dot).toLowerCase(Locale.ROOT);
        if (!extension.matches("\\.[a-z0-9]{1,10}")) {
            throw new RenException("知识库文档扩展名不合法");
        }
        return extension;
    }

    private void deleteTree(Path target) {
        if (!target.startsWith(root) || target.equals(root) || !Files.exists(target)) {
            return;
        }
        try (var paths = Files.walk(target)) {
            paths.sorted(Comparator.reverseOrder()).forEach(this::deleteQuietly);
        } catch (IOException e) {
            throw new RenException("删除知识库原始文档失败");
        }
        if (Files.exists(target)) {
            throw new RenException("删除知识库原始文档失败");
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 上层删除流程会在目录仍存在时报告失败；临时文件清理不覆盖主异常。
        }
    }
}

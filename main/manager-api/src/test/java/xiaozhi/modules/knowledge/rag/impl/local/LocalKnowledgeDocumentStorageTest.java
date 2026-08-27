package xiaozhi.modules.knowledge.rag.impl.local;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockMultipartFile;

import xiaozhi.common.exception.RenException;

class LocalKnowledgeDocumentStorageTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void springCreatesStorageUsingConfiguredRoot() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "localKnowledgeDocumentStorageTest",
                    Map.of("knowledge.document-storage-root", temporaryDirectory.toString())));
            context.register(LocalKnowledgeDocumentStorage.class);
            context.refresh();

            assertNotNull(context.getBean(LocalKnowledgeDocumentStorage.class));
        }
    }

    @Test
    void storesOriginalDocumentUnderValidatedDerivedPath() throws Exception {
        LocalKnowledgeDocumentStorage storage = new LocalKnowledgeDocumentStorage(temporaryDirectory);
        MockMultipartFile file = new MockMultipartFile(
                "file", "慧苑套餐.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                new byte[] { 1, 2, 3 });

        Path stored = storage.store(file, "dataset-1", "document-1", "慧苑套餐.xlsx");

        assertTrue(stored.startsWith(temporaryDirectory));
        assertArrayEquals(new byte[] { 1, 2, 3 }, Files.readAllBytes(stored));
        storage.deleteDocument("dataset-1", "document-1");
        assertFalse(Files.exists(stored));
    }

    @Test
    void rejectsTraversalInIdentifiers() {
        LocalKnowledgeDocumentStorage storage = new LocalKnowledgeDocumentStorage(temporaryDirectory);
        MockMultipartFile file = new MockMultipartFile("file", "doc.txt", "text/plain", "content".getBytes());

        assertThrows(RenException.class,
                () -> storage.store(file, "../outside", "document-1", "doc.txt"));
    }
}

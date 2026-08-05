package xiaozhi.modules.person.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import xiaozhi.common.exception.RenException;

class PersonFaceStorageTest {
    @TempDir
    Path tempDir;

    @Test
    void storesNormalizedJpegInsidePersonScope() throws Exception {
        PersonFaceStorage storage = new PersonFaceStorage(tempDir.toString());
        storage.ensureStorageReady();
        BufferedImage image = new BufferedImage(1200, 800, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, Color.BLUE.getRGB());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        MockMultipartFile file = new MockMultipartFile(
                "file", "portrait.png", "image/png", output.toByteArray());

        PersonFaceStorage.StoredFace stored = storage.store("workspace1", "person1", file);
        byte[] normalized = storage.read(stored.relativePath());
        BufferedImage decoded = ImageIO.read(new java.io.ByteArrayInputStream(normalized));

        assertTrue(stored.relativePath().startsWith("workspace1/person1/"));
        assertEquals("image/jpeg", stored.contentType());
        assertEquals(64, stored.sha256().length());
        assertEquals(640, decoded.getWidth());
        assertEquals(427, decoded.getHeight());
    }

    @Test
    void rejectsMismatchedMimeAndTraversal() {
        PersonFaceStorage storage = new PersonFaceStorage(tempDir.toString());
        storage.ensureStorageReady();
        MockMultipartFile text = new MockMultipartFile(
                "file", "portrait.jpg", "text/plain", "not-an-image".getBytes());

        assertThrows(RenException.class, () -> storage.store("workspace1", "person1", text));
        assertThrows(RenException.class, () -> storage.read("../outside.jpg"));
    }
}

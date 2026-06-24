package xiaozhi.modules.music.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import xiaozhi.modules.music.dto.MusicFileDTO;

class MusicLibraryServiceImplTest {
    @TempDir
    Path tempDir;

    @Test
    void updateDescriptionKeepsAliases() throws Exception {
        Path musicFile = tempDir.resolve("brand-song.mp3");
        Files.write(musicFile, new byte[] {1, 2, 3});

        Path metadataFile = tempDir.resolve(".xiaozhi-music-metadata.json");
        Files.writeString(
                metadataFile,
                """
                {
                  "brand-song.mp3" : {
                    "description" : "旧提示词",
                    "aliases" : [ "品牌星光", "星光之歌" ]
                  }
                }
                """,
                StandardCharsets.UTF_8);

        MusicLibraryServiceImpl service = new MusicLibraryServiceImpl(tempDir.toString(), false, 24000, 48000);

        MusicFileDTO updated = service.updateDescription("brand-song.mp3", "新提示词");

        assertEquals("新提示词", updated.getDescription());
        assertEquals(List.of("品牌星光", "星光之歌"), updated.getAliases());
    }

    @Test
    void updateMetadataWritesDescriptionAndAliases() throws Exception {
        Path musicFile = tempDir.resolve("brand-song.mp3");
        Files.write(musicFile, new byte[] {1, 2, 3});

        MusicLibraryServiceImpl service = new MusicLibraryServiceImpl(tempDir.toString(), false, 24000, 48000);

        MusicFileDTO updated = service.updateMetadata(
                "brand-song.mp3",
                "品牌主题曲",
                List.of("品牌歌", "主题歌", "品牌歌"));

        assertEquals("品牌主题曲", updated.getDescription());
        assertEquals(List.of("品牌歌", "主题歌"), updated.getAliases());
    }
}

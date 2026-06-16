package xiaozhi.modules.music.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.music.dto.MusicFileDTO;
import xiaozhi.modules.music.dto.MusicLibraryDTO;
import xiaozhi.modules.music.service.MusicLibraryService;

@Tag(name = "服务器音乐管理")
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/music")
public class MusicLibraryController {
    private final MusicLibraryService musicLibraryService;

    @GetMapping("/files")
    @Operation(summary = "查询服务器音乐文件")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<MusicLibraryDTO> list(@RequestParam(required = false) String keyword) {
        try {
            return new Result<MusicLibraryDTO>().ok(musicLibraryService.list(keyword));
        } catch (Exception e) {
            log.error("查询服务器音乐文件失败", e);
            return new Result<MusicLibraryDTO>().error(e.getMessage());
        }
    }

    @PostMapping("/upload")
    @Operation(summary = "上传服务器音乐文件")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<List<MusicFileDTO>> upload(@RequestParam("files") MultipartFile[] files) {
        try {
            return new Result<List<MusicFileDTO>>().ok(musicLibraryService.upload(files));
        } catch (Exception e) {
            log.error("上传服务器音乐文件失败", e);
            return new Result<List<MusicFileDTO>>().error(e.getMessage());
        }
    }

    @PutMapping("/files/name")
    @Operation(summary = "重命名服务器音乐文件")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<MusicFileDTO> rename(@RequestParam("path") String path, @RequestParam("newName") String newName) {
        try {
            return new Result<MusicFileDTO>().ok(musicLibraryService.rename(path, newName));
        } catch (Exception e) {
            log.error("重命名服务器音乐文件失败", e);
            return new Result<MusicFileDTO>().error(e.getMessage());
        }
    }

    @DeleteMapping("/files")
    @Operation(summary = "删除服务器音乐文件")
    @RequiresPermissions("sys:role:superAdmin")
    public Result<Void> delete(@RequestParam("path") String path) {
        try {
            musicLibraryService.delete(path);
            return new Result<Void>();
        } catch (Exception e) {
            log.error("删除服务器音乐文件失败", e);
            return new Result<Void>().error(e.getMessage());
        }
    }

    @GetMapping("/play")
    @Operation(summary = "播放服务器音乐文件")
    public void play(@RequestParam("path") String path, HttpServletResponse response) {
        try {
            Path file = musicLibraryService.resolveExistingFile(path);
            response.setContentType(musicLibraryService.getContentType(file));
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + file.getFileName() + "\"");
            response.setContentLengthLong(Files.size(file));
            Files.copy(file, response.getOutputStream());
            response.getOutputStream().flush();
        } catch (IOException | IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        } catch (Exception e) {
            log.error("播放服务器音乐文件失败", e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }
}

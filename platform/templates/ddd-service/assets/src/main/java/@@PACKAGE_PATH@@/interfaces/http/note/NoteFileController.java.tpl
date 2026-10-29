package @@PACKAGE@@.interfaces.http.note;

import cn.dev33.satoken.annotation.SaCheckPermission;
import @@PACKAGE@@.application.command.note.AttachNoteFile;
import @@PACKAGE@@.application.command.note.NotePermissions;
import @@PACKAGE@@.application.query.note.ReadNoteFiles;
import org.domainforge.files.api.FileResponses;
import org.domainforge.shared.ApiResponse;
import org.domainforge.web.api.PlatformJsonApi;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 便笺附件入口：上传与下载都经本服务转发，权限码由注解检查，便笺的数据范围由用例核对；存储不对外暴露。
 *
 * <p>上传接口的路径以 {@code /files} 结尾：入口 Nginx 只对这类路径放宽请求体上限（见 deploy/nginx/locations.inc）。
 */
@RestController
@PlatformJsonApi
public class NoteFileController {
    private final AttachNoteFile writes;
    private final ReadNoteFiles reads;

    /**
     * 只依赖应用用例。
     */
    public NoteFileController(AttachNoteFile writes, ReadNoteFiles reads) {
        this.writes = writes;
        this.reads = reads;
    }

    /**
     * 上传附件（multipart 字段 {@code file}）。
     */
    @SaCheckPermission(NotePermissions.WRITE)
    @PostMapping("/api/notes/{id}/files")
    public ApiResponse<NoteFileResponse> upload(@PathVariable long id, @RequestPart("file") MultipartFile file)
            throws IOException {
        try (InputStream content = file.getInputStream()) {
            return ApiResponse.success(NoteFileResponse.of(
                    writes.attach(id, file.getOriginalFilename(), file.getContentType(), content)));
        }
    }

    /**
     * 附件信息列表。
     */
    @SaCheckPermission(NotePermissions.READ)
    @GetMapping("/api/notes/{id}/files")
    public ApiResponse<List<NoteFileResponse>> list(@PathVariable long id) {
        return ApiResponse.success(reads.list(id).stream().map(NoteFileResponse::of).toList());
    }

    /**
     * 下载附件：作为附件返回，禁止浏览器嗅探与缓存。
     */
    @SaCheckPermission(NotePermissions.READ)
    @GetMapping("/api/notes/{id}/files/{fileId}")
    public ResponseEntity<InputStreamResource> download(@PathVariable long id, @PathVariable String fileId) {
        return FileResponses.attachment(reads.open(id, fileId));
    }

    /**
     * 删除附件。
     */
    @SaCheckPermission(NotePermissions.WRITE)
    @DeleteMapping("/api/notes/{id}/files/{fileId}")
    public ApiResponse<Void> delete(@PathVariable long id, @PathVariable String fileId) {
        writes.detach(id, fileId);
        return ApiResponse.success(null);
    }
}

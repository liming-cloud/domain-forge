package @@PACKAGE@@.interfaces.http.note;

import cn.dev33.satoken.annotation.SaCheckPermission;
import @@PACKAGE@@.application.command.note.ArchiveNote;
import @@PACKAGE@@.application.command.note.NotePermissions;
import @@PACKAGE@@.application.query.note.ReadNoteArchive;
import org.domainforge.shared.ApiResponse;
import org.domainforge.web.api.PlatformJsonApi;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 便笺归档入口：归档写入第二个数据源（独立的归档库）。
 */
@RestController
@PlatformJsonApi
public class NoteArchiveController {
    private final ArchiveNote archive;
    private final ReadNoteArchive reads;

    /**
     * 只依赖应用用例。
     */
    public NoteArchiveController(ArchiveNote archive, ReadNoteArchive reads) {
        this.archive = archive;
        this.reads = reads;
    }

    /**
     * 归档（重复归档覆盖为当前版本）。
     */
    @SaCheckPermission(NotePermissions.WRITE)
    @PostMapping("/api/notes/{id}/archive")
    public ApiResponse<ArchivedNoteResponse> archive(@PathVariable long id) {
        return ApiResponse.success(ArchivedNoteResponse.of(archive.archive(id)));
    }

    /**
     * 读取归档快照。
     */
    @SaCheckPermission(NotePermissions.READ)
    @GetMapping("/api/notes/{id}/archive")
    public ApiResponse<ArchivedNoteResponse> read(@PathVariable long id) {
        return ApiResponse.success(ArchivedNoteResponse.of(reads.read(id)));
    }
}

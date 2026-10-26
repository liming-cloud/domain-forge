package @@PACKAGE@@.interfaces.http.note;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import @@PACKAGE@@.application.command.note.NotePermissions;
import @@PACKAGE@@.application.command.note.WriteNote;
import @@PACKAGE@@.application.query.note.ListVisibleNotes;
import @@PACKAGE@@.application.query.note.NoteQueries;
import org.domainforge.access.api.CurrentAccess;
import org.domainforge.shared.ApiResponse;
import org.domainforge.web.api.PlatformJsonApi;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 便笺示例入口：三条路径分别演示命令、纯查询与需要编排的查询。
 *
 * <p>权限码由原生注解在入口检查；数据范围由应用用例按对象核对。
 */
@RestController
@PlatformJsonApi
public class NoteController {
    private final WriteNote writes;
    private final NoteQueries queries;
    private final ListVisibleNotes visible;
    private final CurrentAccess access;

    /**
     * 只依赖应用用例、查询合同与访问组件公开接口。
     */
    public NoteController(WriteNote writes, NoteQueries queries, ListVisibleNotes visible, CurrentAccess access) {
        this.writes = writes;
        this.queries = queries;
        this.visible = visible;
        this.access = access;
    }

    /**
     * 命令：在写范围内新建便笺。
     */
    @SaCheckPermission(NotePermissions.WRITE)
    @PostMapping("/api/notes")
    public ApiResponse<NoteChangeResponse> create(@RequestBody CreateNoteRequest input) {
        return ApiResponse.success(NoteChangeResponse.of(
                writes.create(input.unitId(), input.title(), input.content())));
    }

    /**
     * 命令：修改写范围内的便笺，须携带读取时的版本。
     */
    @SaCheckPermission(NotePermissions.WRITE)
    @PutMapping("/api/notes/{id}")
    public ApiResponse<NoteChangeResponse> edit(@PathVariable long id, @RequestBody EditNoteRequest input) {
        return ApiResponse.success(NoteChangeResponse.of(
                writes.edit(id, input.expectedVersion(), input.title(), input.content())));
    }

    /**
     * 纯查询：本人创建的便笺，只需登录。
     */
    @SaCheckLogin
    @GetMapping("/api/me/notes")
    public ApiResponse<List<NoteResponse>> mine(
            @RequestParam(defaultValue = "0") long after, @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(queries.owned(access.current().accountId(), after, limit).stream()
                .map(NoteResponse::of).toList());
    }

    /**
     * 需要编排的查询：按 IAM 读范围过滤。
     */
    @SaCheckPermission(NotePermissions.READ)
    @GetMapping("/api/notes")
    public ApiResponse<List<NoteResponse>> list(
            @RequestParam(defaultValue = "0") long after, @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(visible.list(after, limit).stream().map(NoteResponse::of).toList());
    }
}

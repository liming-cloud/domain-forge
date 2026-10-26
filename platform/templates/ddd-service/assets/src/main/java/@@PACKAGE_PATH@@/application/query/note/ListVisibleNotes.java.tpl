package @@PACKAGE@@.application.query.note;

import @@PACKAGE@@.application.command.note.NotePermissions;
import @@PACKAGE@@.application.port.access.DataScopes;
import org.domainforge.access.api.CurrentAccess;
import org.domainforge.shared.CurrentScope;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 需要编排的查询：先向 IAM 取得读范围，再把范围映射为参数绑定的 SQL 条件；空范围直接返回空结果，不放行。
 */
@Service
public class ListVisibleNotes {
    private final CurrentAccess access;
    private final DataScopes scopes;
    private final NoteQueries queries;

    /**
     * 只依赖访问组件公开接口、本服务端口与查询合同。
     */
    public ListVisibleNotes(CurrentAccess access, DataScopes scopes, NoteQueries queries) {
        this.access = access;
        this.scopes = scopes;
        this.queries = queries;
    }

    /**
     * 返回当前主体读范围内的便笺。
     */
    public List<NoteView> list(long after, int limit) {
        long account = access.current().accountId();
        CurrentScope scope = scopes.scope(NotePermissions.READ);
        var filter = new NoteFilter(account, scope.all(), scope.self(), scope.unitIds(), after, limit);
        return scope.empty() ? List.of() : queries.visible(filter);
    }
}

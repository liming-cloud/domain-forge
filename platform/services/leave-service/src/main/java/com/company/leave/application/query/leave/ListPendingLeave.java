package com.company.leave.application.query.leave;

import com.company.leave.application.leave.LeavePermissions;
import com.company.leave.application.port.access.DataScopes;
import org.domainforge.shared.CurrentScope;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 待我审批：先向 IAM 取得审批权限的范围，再映射为参数绑定的 SQL 条件；没有部门也不是 ALL 时直接返回空结果，不放行。
 *
 * <p>审批只看部门，本人范围（SELF）不构成审批任何申请的依据。
 */
@Service
public class ListPendingLeave {
    private final DataScopes scopes;
    private final LeaveQueries queries;

    /**
     * 只依赖本服务端口与查询合同。
     */
    public ListPendingLeave(DataScopes scopes, LeaveQueries queries) {
        this.scopes = scopes;
        this.queries = queries;
    }

    /**
     * 返回当前审批人范围内部门的待审申请。
     */
    public List<LeaveRequestView> list(long after, int limit) {
        CurrentScope scope = scopes.scope(LeavePermissions.APPROVE);
        var filter = new PendingFilter(scope.all(), scope.unitIds(), after, limit);
        return !scope.all() && scope.unitIds().isEmpty() ? List.of() : queries.pending(filter);
    }
}

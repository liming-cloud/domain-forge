package com.company.leave.application.command.leave;

import com.company.leave.application.leave.LeavePermissions;
import com.company.leave.application.leave.LeaveRejected;
import com.company.leave.application.port.access.DataScopes;
import com.company.leave.domain.model.leave.LeaveLedger;
import com.company.leave.domain.model.leave.LeaveRequest;
import com.company.leave.domain.repository.LeaveLedgerRepository;
import org.domainforge.shared.CurrentScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.BiFunction;

/**
 * 审批人的命令：通过与驳回。审批人只能处理审批权限范围内部门的申请（ALL 可处理全部），范围外的申请按不存在处理；
 * 通过时扣减余额、余额不足拒绝，由申请人的年假账户判断。
 *
 * <p>不加锁：保存时以读取到的账户版本为条件更新。同一申请人的两份申请被同时通过时只有一个成功，另一个返回 409
 * {@code VERSION_CONFLICT}，重试时按最新余额重新判断，余额不会被超扣。
 */
@Service
public class ApproveLeave {
    private final DataScopes scopes;
    private final LeaveLedgerRepository ledgers;

    /**
     * 只依赖本服务端口与仓储。
     */
    public ApproveLeave(DataScopes scopes, LeaveLedgerRepository ledgers) {
        this.scopes = scopes;
        this.ledgers = ledgers;
    }

    /**
     * 通过待审的申请并扣减申请人的余额。
     */
    @Transactional
    public LeaveChange approve(long requestId) {
        return decide(requestId, LeaveLedger::approve);
    }

    /**
     * 驳回待审的申请，余额不变。
     */
    @Transactional
    public LeaveChange reject(long requestId) {
        return decide(requestId, LeaveLedger::reject);
    }

    private LeaveChange decide(long requestId, BiFunction<LeaveLedger, Long, LeaveLedger> decision) {
        CurrentScope scope = scopes.scope(LeavePermissions.APPROVE);
        LeaveLedger current = ledgers.findByRequest(requestId);
        LeaveRequest request = current == null ? null : current.request(requestId);
        if (current == null || request == null || !scope.all() && !scope.unitIds().contains(request.unitId()))
            throw LeaveRejected.requestNotFound();
        LeaveLedger changed = decision.apply(current, requestId);
        if (!ledgers.update(changed, current.version())) throw LeaveRejected.versionConflict();
        return ApplyLeave.result(changed, requestId);
    }
}

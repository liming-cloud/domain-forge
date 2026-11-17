package com.company.leave.application.command.leave;

import com.company.leave.application.leave.LeavePermissions;
import com.company.leave.application.leave.LeaveRejected;
import com.company.leave.application.port.access.DataScopes;
import com.company.leave.application.port.operation.Identifiers;
import com.company.leave.domain.model.leave.LeaveLedger;
import com.company.leave.domain.model.leave.LeavePeriod;
import com.company.leave.domain.model.leave.LeaveRequest;
import com.company.leave.domain.model.leave.LeaveRuleViolation;
import com.company.leave.domain.repository.LeaveLedgerRepository;
import org.domainforge.access.api.AccessException;
import org.domainforge.access.api.AccessFailure;
import org.domainforge.access.api.CurrentAccess;
import org.domainforge.shared.CurrentScope;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.function.BiFunction;

/**
 * 申请人的命令：起草、提交、撤回。申请人只能操作自己账户中的申请，别人的申请按不存在处理；时段重叠等规则由年假账户判断。
 *
 * <p>不加锁：保存时以读取到的账户版本为条件更新，同一人的并发修改只有一个成功，其余返回 409 {@code VERSION_CONFLICT}。
 * 申请提交后内容不再变化，状态迁移由聚合按状态机判断，因此客户端不需要携带版本。
 */
@Service
public class ApplyLeave {
    private final CurrentAccess access;
    private final DataScopes scopes;
    private final LeaveLedgerRepository ledgers;
    private final Identifiers ids;

    /**
     * 只依赖访问组件公开接口、本服务端口与仓储。
     */
    public ApplyLeave(CurrentAccess access, DataScopes scopes, LeaveLedgerRepository ledgers, Identifiers ids) {
        this.access = access;
        this.scopes = scopes;
        this.ledgers = ledgers;
        this.ids = ids;
    }

    /**
     * 起草一份挂靠在给定部门的申请：部门与起止日期必填（400），部门须在申请权限的范围内（ALL 可挂靠任意部门），否则 403；
     * 本人第一次申请时开立年假账户。
     */
    @Transactional
    public LeaveChange draft(long unitId, @Nullable LocalDate start, @Nullable LocalDate end) {
        if (unitId <= 0 || start == null || end == null) throw LeaveRuleViolation.invalid("部门与起止日期必填");
        LeavePeriod period = new LeavePeriod(start, end);
        long account = access.current().accountId();
        CurrentScope scope = scopes.scope(LeavePermissions.APPLY);
        if (!scope.all() && !scope.unitIds().contains(unitId)) throw new AccessException(AccessFailure.FORBIDDEN);
        long requestId = ids.next();
        LeaveLedger current = ledgers.find(account);
        LeaveLedger changed = (current == null ? LeaveLedger.open(account) : current).draft(requestId, unitId, period);
        boolean saved = current == null ? ledgers.insert(changed) : ledgers.update(changed, current.version());
        if (!saved) throw LeaveRejected.versionConflict();
        return result(changed, requestId);
    }

    /**
     * 本人的草稿提交审批。
     */
    @Transactional
    public LeaveChange submit(long requestId) {
        return own(requestId, LeaveLedger::submit);
    }

    /**
     * 本人在审批前撤回申请（草稿或待审）。
     */
    @Transactional
    public LeaveChange withdraw(long requestId) {
        return own(requestId, LeaveLedger::withdraw);
    }

    private LeaveChange own(long requestId, BiFunction<LeaveLedger, Long, LeaveLedger> transition) {
        LeaveLedger current = ledgers.find(access.current().accountId());
        if (current == null || current.request(requestId) == null) throw LeaveRejected.requestNotFound();
        LeaveLedger changed = transition.apply(current, requestId);
        if (!ledgers.update(changed, current.version())) throw LeaveRejected.versionConflict();
        return result(changed, requestId);
    }

    static LeaveChange result(LeaveLedger ledger, long requestId) {
        LeaveRequest request = ledger.request(requestId);
        if (request == null) throw LeaveRejected.requestNotFound();
        return new LeaveChange(requestId, request.status().name());
    }
}

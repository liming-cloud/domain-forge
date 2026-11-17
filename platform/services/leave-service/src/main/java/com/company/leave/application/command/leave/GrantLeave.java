package com.company.leave.application.command.leave;

import com.company.leave.application.leave.LeavePermissions;
import com.company.leave.application.leave.LeaveRejected;
import com.company.leave.application.port.access.DataScopes;
import com.company.leave.domain.model.leave.LeaveLedger;
import com.company.leave.domain.repository.LeaveLedgerRepository;
import org.domainforge.access.api.AccessException;
import org.domainforge.access.api.AccessFailure;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 发放年假：把天数加到员工的余额上，员工还没有账户时开立。需要发放权限的 ALL 范围，否则 403；
 * 并发修改同一员工账户时返回 409 {@code VERSION_CONFLICT}，不覆盖别人的结果。
 */
@Service
public class GrantLeave {
    private final DataScopes scopes;
    private final LeaveLedgerRepository ledgers;

    /**
     * 只依赖本服务端口与仓储。
     */
    public GrantLeave(DataScopes scopes, LeaveLedgerRepository ledgers) {
        this.scopes = scopes;
        this.ledgers = ledgers;
    }

    /**
     * 给员工发放天数（1 至 366），返回发放后的余额。账号是否存在由 IAM 管理，本服务不核对。
     */
    @Transactional
    public BalanceChange grant(long accountId, int days) {
        if (!scopes.scope(LeavePermissions.GRANT).all()) throw new AccessException(AccessFailure.FORBIDDEN);
        LeaveLedger current = ledgers.find(accountId);
        LeaveLedger changed = (current == null ? LeaveLedger.open(accountId) : current).grant(days);
        boolean saved = current == null ? ledgers.insert(changed) : ledgers.update(changed, current.version());
        if (!saved) throw LeaveRejected.versionConflict();
        return new BalanceChange(accountId, changed.remainingDays());
    }
}

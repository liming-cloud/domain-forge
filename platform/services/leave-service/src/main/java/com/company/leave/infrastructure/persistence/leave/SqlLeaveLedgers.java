package com.company.leave.infrastructure.persistence.leave;

import com.company.leave.domain.model.leave.LeaveLedger;
import com.company.leave.domain.model.leave.LeavePeriod;
import com.company.leave.domain.model.leave.LeaveRequest;
import com.company.leave.domain.model.leave.LeaveStatus;
import com.company.leave.domain.repository.LeaveLedgerRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/**
 * MyBatis 年假账户仓储：账户与申请一起读写。保存时先以版本为条件更新账户行，写入成功后再写入申请（新申请插入，已有申请更新状态）。
 */
@Repository
public class SqlLeaveLedgers implements LeaveLedgerRepository {
    private final LeaveLedgerMapper mapper;

    /**
     * 绑定年假账户 Mapper。
     */
    public SqlLeaveLedgers(LeaveLedgerMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public @Nullable LeaveLedger find(long accountId) {
        return restore(mapper.find(accountId));
    }

    @Override
    public @Nullable LeaveLedger findByRequest(long requestId) {
        return restore(mapper.findByRequest(requestId));
    }

    /**
     * 账户行已存在（并发开立）时不写入，返回 false。
     */
    @Override
    public boolean insert(LeaveLedger ledger) {
        if (mapper.insert(row(ledger)) != 1) return false;
        saveRequests(ledger);
        return true;
    }

    @Override
    public boolean update(LeaveLedger ledger, long expected) {
        if (mapper.update(row(ledger), expected) != 1) return false;
        saveRequests(ledger);
        return true;
    }

    private @Nullable LeaveLedger restore(@Nullable LedgerRow row) {
        if (row == null) return null;
        var requests = mapper.requests(row.accountId()).stream()
                .map(r -> new LeaveRequest(r.id(), r.unitId(), new LeavePeriod(r.startDate(), r.endDate()),
                        LeaveStatus.valueOf(r.status())))
                .toList();
        return LeaveLedger.restore(row.accountId(), row.remainingDays(), requests, row.version());
    }

    private void saveRequests(LeaveLedger ledger) {
        ledger.requests().forEach(r -> mapper.saveRequest(ledger.accountId(),
                new RequestRow(r.id(), r.unitId(), r.period().start(), r.period().end(), r.status().name())));
    }

    private static LedgerRow row(LeaveLedger ledger) {
        return new LedgerRow(ledger.accountId(), ledger.remainingDays(), ledger.version());
    }
}

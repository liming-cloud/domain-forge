package com.company.leave.infrastructure.query.leave;

import com.company.leave.application.leave.LeaveRejected;
import com.company.leave.application.query.leave.LeaveBalanceView;
import com.company.leave.application.query.leave.LeaveQueries;
import com.company.leave.application.query.leave.LeaveRequestView;
import com.company.leave.application.query.leave.PendingFilter;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 请假查询实现。
 */
@Repository
public class SqlLeaveQueries implements LeaveQueries {
    private final LeaveQueryMapper mapper;

    /**
     * 只绑定查询 Mapper。
     */
    public SqlLeaveQueries(LeaveQueryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<LeaveRequestView> mine(long applicantId, long after, int limit) {
        if (after < 0 || limit < 1 || limit > 100) throw LeaveRejected.invalidPage();
        return mapper.mine(applicantId, after, limit);
    }

    @Override
    public List<LeaveRequestView> pending(PendingFilter filter) {
        return mapper.pending(filter);
    }

    @Override
    public LeaveBalanceView balance(long accountId) {
        return mapper.balance(accountId);
    }
}

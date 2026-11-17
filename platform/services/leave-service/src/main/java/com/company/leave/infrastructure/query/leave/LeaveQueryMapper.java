package com.company.leave.infrastructure.query.leave;

import com.company.leave.application.query.leave.LeaveBalanceView;
import com.company.leave.application.query.leave.LeaveRequestView;
import com.company.leave.application.query.leave.PendingFilter;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 请假查询 SQL；范围条件全部参数绑定。
 */
@org.apache.ibatis.annotations.Mapper
public interface LeaveQueryMapper {
    List<LeaveRequestView> mine(@Param("applicant") long applicant, @Param("after") long after,
            @Param("limit") int limit);

    List<LeaveRequestView> pending(@Param("filter") PendingFilter filter);

    LeaveBalanceView balance(@Param("account") long account);
}

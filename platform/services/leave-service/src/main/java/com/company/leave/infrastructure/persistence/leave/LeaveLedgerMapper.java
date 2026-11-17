package com.company.leave.infrastructure.persistence.leave;

import org.apache.ibatis.annotations.Param;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * 年假账户持久化 SQL，写在 mapper/leave/LeaveLedgerMapper.xml。
 */
@org.apache.ibatis.annotations.Mapper
public interface LeaveLedgerMapper {
    @Nullable
    LedgerRow find(long accountId);

    @Nullable
    LedgerRow findByRequest(long requestId);

    List<RequestRow> requests(long accountId);

    int insert(LedgerRow row);

    int update(@Param("row") LedgerRow row, @Param("expected") long expected);

    int saveRequest(@Param("accountId") long accountId, @Param("request") RequestRow request);
}

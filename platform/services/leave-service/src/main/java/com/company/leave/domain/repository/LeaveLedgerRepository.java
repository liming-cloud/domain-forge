package com.company.leave.domain.repository;

import com.company.leave.domain.model.leave.LeaveLedger;
import org.jspecify.annotations.Nullable;

/**
 * 年假账户仓储（申请随账户一起读写）；不存在返回 null，版本冲突不能当作成功。
 */
public interface LeaveLedgerRepository {
    /**
     * 读取员工的年假账户，不加锁；员工从未发放年假也从未申请时返回 null。
     */
    @Nullable
    LeaveLedger find(long accountId);

    /**
     * 读取包含该申请的年假账户，不加锁；申请不存在返回 null。
     */
    @Nullable
    LeaveLedger findByRequest(long requestId);

    /**
     * 保存新开立的账户；同一员工的账户已被并发开立（未写入）返回 false，由应用层判定为冲突。
     */
    boolean insert(LeaveLedger ledger);

    /**
     * 以原版本为条件保存账户及其申请；账户已被他人修改（零行）返回 false，由应用层判定为冲突。
     */
    boolean update(LeaveLedger ledger, long expectedVersion);
}

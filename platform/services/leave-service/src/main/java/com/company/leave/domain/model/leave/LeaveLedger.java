package com.company.leave.domain.model.leave;

import org.jmolecules.ddd.annotation.AggregateRoot;
import org.jmolecules.ddd.annotation.Identity;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * 一名员工的年假账户：年假余额与本人的全部请假申请。
 *
 * <p>两条规则跨越同一人的多份申请，因此由这一个聚合维护：同一人占用时段的申请（草稿、待审、已通过）互不重叠；申请通过时
 * 从余额扣减天数，余额不能为负。申请只能在审批前撤回或被驳回，因此不存在“已扣减后又撤回或驳回”的申请，余额不需要退回。
 *
 * <p>每次变化返回新的账户，版本加一，原账户不变；保存时以原版本为条件更新，同一人的并发修改只有一个成功，
 * 余额因此不会被并发审批超扣。谁能操作哪份申请（申请人本人、审批人的部门范围）是授权决定，在应用层核对。
 */
@AggregateRoot
public final class LeaveLedger {
    /**
     * 单次发放的最多天数。
     */
    public static final int MAX_GRANT_DAYS = 366;

    @Identity
    private final long accountId;
    private final int remainingDays;
    private final List<LeaveRequest> requests;
    private final long version;

    private LeaveLedger(long accountId, int remainingDays, List<LeaveRequest> requests, long version) {
        if (accountId <= 0 || remainingDays < 0 || version < 1 || requests == null)
            throw LeaveRuleViolation.invalid("年假账户身份、余额或版本无效");
        this.accountId = accountId;
        this.remainingDays = remainingDays;
        this.requests = List.copyOf(requests);
        this.version = version;
        checkRequests(this.requests);
    }

    private static void checkRequests(List<LeaveRequest> requests) {
        if (requests.stream().map(LeaveRequest::id).distinct().count() != requests.size())
            throw LeaveRuleViolation.invalid("请假申请身份重复");
        List<LeaveRequest> occupying = requests.stream().filter(r -> r.status().occupiesPeriod()).toList();
        for (int i = 0; i < occupying.size(); i++) {
            for (int j = i + 1; j < occupying.size(); j++) {
                if (occupying.get(i).period().overlaps(occupying.get(j).period()))
                    throw LeaveRuleViolation.periodOverlap();
            }
        }
    }

    /**
     * 为员工开立空账户：余额为零，没有申请。
     */
    public static LeaveLedger open(long accountId) {
        return new LeaveLedger(accountId, 0, List.of(), 1);
    }

    /**
     * 从存储恢复；恢复也校验全部不变式。
     */
    public static LeaveLedger restore(long accountId, int remainingDays, List<LeaveRequest> requests, long version) {
        return new LeaveLedger(accountId, remainingDays, requests, version);
    }

    /**
     * 发放年假天数（1 至 {@link #MAX_GRANT_DAYS}），加到余额上。
     */
    public LeaveLedger grant(int days) {
        if (days < 1 || days > MAX_GRANT_DAYS)
            throw LeaveRuleViolation.invalid("单次发放天数须为1至" + MAX_GRANT_DAYS);
        return new LeaveLedger(accountId, Math.addExact(remainingDays, days), requests, version + 1);
    }

    /**
     * 起草一份申请：时段与本人占用时段的申请重叠时拒绝（{@code LEAVE_PERIOD_OVERLAP}）。起草不检查余额，余额在通过时扣减。
     */
    public LeaveLedger draft(long requestId, long unitId, LeavePeriod period) {
        if (request(requestId) != null) throw LeaveRuleViolation.invalid("请假申请身份重复");
        List<LeaveRequest> next = new ArrayList<>(requests);
        next.add(new LeaveRequest(requestId, unitId, period, LeaveStatus.DRAFT));
        return new LeaveLedger(accountId, remainingDays, next, version + 1);
    }

    /**
     * 草稿提交审批。
     */
    public LeaveLedger submit(long requestId) {
        return change(requestId, LeaveRequest::submit);
    }

    /**
     * 申请人在审批前（草稿或待审）撤回；没有扣减过余额，余额不变。
     */
    public LeaveLedger withdraw(long requestId) {
        return change(requestId, LeaveRequest::withdraw);
    }

    /**
     * 驳回待审的申请；没有扣减过余额，余额不变。
     */
    public LeaveLedger reject(long requestId) {
        return change(requestId, LeaveRequest::reject);
    }

    /**
     * 通过待审的申请并扣减其天数；余额不足时拒绝（{@code LEAVE_BALANCE_INSUFFICIENT}），申请保持待审。
     */
    public LeaveLedger approve(long requestId) {
        LeaveRequest approved = existing(requestId).approve();
        int days = approved.period().days();
        if (days > remainingDays) throw LeaveRuleViolation.balanceInsufficient();
        return replace(approved, remainingDays - days);
    }

    private LeaveLedger change(long requestId, UnaryOperator<LeaveRequest> transition) {
        return replace(transition.apply(existing(requestId)), remainingDays);
    }

    private LeaveLedger replace(LeaveRequest changed, int remaining) {
        List<LeaveRequest> next = requests.stream().map(r -> r.id() == changed.id() ? changed : r).toList();
        return new LeaveLedger(accountId, remaining, next, version + 1);
    }

    private LeaveRequest existing(long requestId) {
        LeaveRequest found = request(requestId);
        if (found == null) throw LeaveRuleViolation.invalid("请假申请不属于该账户");
        return found;
    }

    /**
     * 按身份取本账户的申请；不属于本账户返回 null。
     */
    public @Nullable LeaveRequest request(long requestId) {
        return requests.stream().filter(r -> r.id() == requestId).findFirst().orElse(null);
    }

    /**
     * 员工账号 ID（IAM 的稳定 ID），也是账户身份。
     */
    public long accountId() {
        return accountId;
    }

    /**
     * 剩余年假天数，不为负。
     */
    public int remainingDays() {
        return remainingDays;
    }

    /**
     * 本人的全部申请（不可变视图）。
     */
    public List<LeaveRequest> requests() {
        return requests;
    }

    /**
     * 乐观锁版本，每次变化加一。
     */
    public long version() {
        return version;
    }
}

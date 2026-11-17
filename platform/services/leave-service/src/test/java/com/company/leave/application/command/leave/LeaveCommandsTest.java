package com.company.leave.application.command.leave;

import com.company.leave.application.leave.LeavePermissions;
import com.company.leave.domain.model.leave.LeaveLedger;
import com.company.leave.domain.model.leave.LeavePeriod;
import com.company.leave.domain.repository.LeaveLedgerRepository;
import org.domainforge.access.api.AccessException;
import org.domainforge.access.api.AccessFailure;
import org.domainforge.shared.BusinessRuleViolation;
import org.domainforge.shared.CurrentScope;
import org.domainforge.shared.CurrentSubject;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 请假命令用例的快速测试：仓储用内存 Fake，不起 Spring 与数据库。拒绝时断言没有写入。
 */
class LeaveCommandsTest {
    private static final long APPLICANT = 7;
    private static final long OTHER = 8;
    private static final long UNIT = 10;
    private static final LocalDate FIRST = LocalDate.of(2026, 12, 1);
    private static final LocalDate THIRD = LocalDate.of(2026, 12, 3);

    private final InMemoryLedgers ledgers = new InMemoryLedgers();
    private final AtomicLong ids = new AtomicLong(100);
    private long current = APPLICANT;
    private final Map<String, CurrentScope> scopes = new HashMap<>();
    private final ApplyLeave apply = new ApplyLeave(() -> new CurrentSubject(current, Set.of()),
            permission -> scopes.getOrDefault(permission, CurrentScope.none()), ledgers, ids::incrementAndGet);
    private final ApproveLeave approve =
            new ApproveLeave(permission -> scopes.getOrDefault(permission, CurrentScope.none()), ledgers);
    private final GrantLeave grant =
            new GrantLeave(permission -> scopes.getOrDefault(permission, CurrentScope.none()), ledgers);

    private static CurrentScope units(Long... units) {
        return new CurrentScope(false, false, Set.of(units));
    }

    // 申请人余额 10 天，在 UNIT 部门有一份 3 天的待审申请，返回申请 ID。
    private long pendingRequest() {
        ledgers.saved.put(APPLICANT, LeaveLedger.open(APPLICANT).grant(10)
                .draft(1, UNIT, new LeavePeriod(FIRST, THIRD)).submit(1));
        ledgers.writes = 0;
        return 1;
    }

    private void assertRejected(String code, Executable action) {
        assertEquals(code, assertThrows(BusinessRuleViolation.class, action).code());
        assertEquals(0, ledgers.writes);
    }

    private void assertForbidden(Executable action) {
        assertEquals(AccessFailure.FORBIDDEN, assertThrows(AccessException.class, action).failure());
        assertEquals(0, ledgers.writes);
    }

    @Test
    void firstDraftOpensLedgerInAUnitOfTheApplyScope() {
        scopes.put(LeavePermissions.APPLY, units(UNIT));

        LeaveChange change = apply.draft(UNIT, FIRST, THIRD);

        assertEquals(new LeaveChange(101, "DRAFT"), change);
        assertEquals(1, ledgers.writes);
        assertEquals(0, ledgers.saved.get(APPLICANT).remainingDays());
        assertEquals("PENDING", apply.submit(101).status());
    }

    @Test
    void draftOutsideApplyScopeIsForbidden() {
        scopes.put(LeavePermissions.APPLY, new CurrentScope(false, true, Set.of(UNIT)));

        assertForbidden(() -> apply.draft(20, FIRST, THIRD));
        assertRejected("LEAVE_INVALID", () -> apply.draft(UNIT, null, THIRD));
    }

    @Test
    void concurrentFirstDraftOfTheSameApplicantIsConflict() {
        scopes.put(LeavePermissions.APPLY, units(UNIT));
        ledgers.afterRead = () -> ledgers.saved.putIfAbsent(APPLICANT, LeaveLedger.open(APPLICANT));

        assertRejected("VERSION_CONFLICT", () -> apply.draft(UNIT, FIRST, THIRD));
    }

    @Test
    void overlappingDraftIsRejectedByLedger() {
        pendingRequest();
        scopes.put(LeavePermissions.APPLY, units(UNIT));

        assertRejected("LEAVE_PERIOD_OVERLAP", () -> apply.draft(UNIT, THIRD, THIRD.plusDays(1)));
    }

    @Test
    void applicantWithdrawsOwnRequestButNotSomeoneElses() {
        long id = pendingRequest();
        current = OTHER;
        assertRejected("LEAVE_REQUEST_NOT_FOUND", () -> apply.withdraw(id));

        current = APPLICANT;
        assertEquals("WITHDRAWN", apply.withdraw(id).status());
        assertEquals(10, ledgers.saved.get(APPLICANT).remainingDays());
    }

    @Test
    void approvalInScopeDeductsBalance() {
        long id = pendingRequest();
        scopes.put(LeavePermissions.APPROVE, units(UNIT));

        assertEquals(new LeaveChange(id, "APPROVED"), approve.approve(id));
        assertEquals(7, ledgers.saved.get(APPLICANT).remainingDays());
    }

    @Test
    void approvalOutsideScopeIsReportedAsNotFoundAndChangesNothing() {
        long id = pendingRequest();
        scopes.put(LeavePermissions.APPROVE, new CurrentScope(false, true, Set.of(20L)));

        assertRejected("LEAVE_REQUEST_NOT_FOUND", () -> approve.approve(id));
        assertRejected("LEAVE_REQUEST_NOT_FOUND", () -> approve.reject(id));
        assertRejected("LEAVE_REQUEST_NOT_FOUND", () -> approve.approve(999));
        assertEquals(10, ledgers.saved.get(APPLICANT).remainingDays());
    }

    @Test
    void ledgerChangedAfterReadMakesApprovalAConflict() {
        long id = pendingRequest();
        scopes.put(LeavePermissions.APPROVE, new CurrentScope(true, false, Set.of()));
        ledgers.afterRead = () -> ledgers.saved.computeIfPresent(APPLICANT, (account, ledger) -> ledger.grant(1));

        assertRejected("VERSION_CONFLICT", () -> approve.approve(id));
    }

    @Test
    void grantNeedsAllScope() {
        scopes.put(LeavePermissions.GRANT, units(UNIT));
        assertForbidden(() -> grant.grant(APPLICANT, 5));

        scopes.put(LeavePermissions.GRANT, new CurrentScope(true, false, Set.of()));
        assertEquals(new BalanceChange(APPLICANT, 5), grant.grant(APPLICANT, 5));
        assertEquals(new BalanceChange(APPLICANT, 8), grant.grant(APPLICANT, 3));
    }

    /**
     * 按版本条件保存的内存仓储；{@code afterRead} 模拟另一个请求在本次读取之后改了账户。
     */
    private static final class InMemoryLedgers implements LeaveLedgerRepository {
        private final Map<Long, LeaveLedger> saved = new HashMap<>();
        private int writes;
        private Runnable afterRead = () -> {
        };

        @Override
        public @Nullable LeaveLedger find(long accountId) {
            LeaveLedger ledger = saved.get(accountId);
            afterRead.run();
            return ledger;
        }

        @Override
        public @Nullable LeaveLedger findByRequest(long requestId) {
            LeaveLedger ledger = saved.values().stream().filter(l -> l.request(requestId) != null).findFirst()
                    .orElse(null);
            afterRead.run();
            return ledger;
        }

        @Override
        public boolean insert(LeaveLedger ledger) {
            if (saved.putIfAbsent(ledger.accountId(), ledger) != null) return false;
            writes++;
            return true;
        }

        @Override
        public boolean update(LeaveLedger ledger, long expectedVersion) {
            LeaveLedger current = saved.get(ledger.accountId());
            if (current == null || current.version() != expectedVersion) return false;
            saved.put(ledger.accountId(), ledger);
            writes++;
            return true;
        }
    }
}

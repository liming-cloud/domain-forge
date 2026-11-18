package com.company.leave.domain.model.leave;

import org.domainforge.shared.BusinessRuleViolation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeaveLedgerTest {
    private static final long ACCOUNT = 7;
    private static final long UNIT = 10;

    private static LeavePeriod days(int firstDay, int lastDay) {
        return new LeavePeriod(LocalDate.of(2026, 12, firstDay), LocalDate.of(2026, 12, lastDay));
    }

    // 余额 10 天，申请 1 为 12 月 1 日至 3 日（3 天）的草稿。
    private static LeaveLedger withDraft() {
        return LeaveLedger.open(ACCOUNT).grant(10).draft(1, UNIT, days(1, 3));
    }

    private static LeaveStatus status(LeaveLedger ledger, long requestId) {
        return ledger.request(requestId).status();
    }

    private static void assertViolation(String code, Executable action) {
        assertEquals(code, assertThrows(BusinessRuleViolation.class, action).code());
    }

    @Test
    void periodCountsBothEndsAndRejectsReversedOrMissingDates() {
        assertEquals(3, days(1, 3).days());
        assertEquals(1, days(5, 5).days());
        assertViolation("LEAVE_INVALID", () -> days(3, 1));
        assertViolation("LEAVE_INVALID", () -> new LeavePeriod(null, LocalDate.of(2026, 12, 1)));
        assertViolation("LEAVE_INVALID",
                () -> new LeavePeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 2)));
    }

    @Test
    void periodsSharingADayOverlap() {
        assertTrue(days(1, 3).overlaps(days(3, 5)));
        assertTrue(days(1, 10).overlaps(days(4, 5)));
        assertFalse(days(1, 3).overlaps(days(4, 5)));
    }

    @Test
    void draftSubmitApproveDeductsDaysAndBumpsVersion() {
        LeaveLedger draft = withDraft();
        LeaveLedger pending = draft.submit(1);
        LeaveLedger approved = pending.approve(1);

        assertEquals(LeaveStatus.DRAFT, status(draft, 1));
        assertEquals(LeaveStatus.PENDING, status(pending, 1));
        assertEquals(LeaveStatus.APPROVED, status(approved, 1));
        assertEquals(10, pending.remainingDays());
        assertEquals(7, approved.remainingDays());
        assertEquals(pending.version() + 1, approved.version());
        assertEquals(LeaveStatus.PENDING, status(pending, 1), "原账户不变");
    }

    @Test
    void rejectAndWithdrawLeaveBalanceUntouched() {
        LeaveLedger pending = withDraft().submit(1);

        assertEquals(LeaveStatus.REJECTED, status(pending.reject(1), 1));
        assertEquals(10, pending.reject(1).remainingDays());
        assertEquals(LeaveStatus.WITHDRAWN, status(pending.withdraw(1), 1));
        assertEquals(10, pending.withdraw(1).remainingDays());
        assertEquals(LeaveStatus.WITHDRAWN, status(withDraft().withdraw(1), 1));
    }

    @Test
    void everyIllegalTransitionIsAStateConflict() {
        LeaveLedger draft = withDraft();
        LeaveLedger pending = draft.submit(1);
        String conflict = "LEAVE_STATE_CONFLICT";

        assertViolation(conflict, () -> draft.approve(1));
        assertViolation(conflict, () -> draft.reject(1));
        assertViolation(conflict, () -> pending.submit(1));
        for (LeaveLedger ended : List.of(pending.approve(1), pending.reject(1), pending.withdraw(1))) {
            assertViolation(conflict, () -> ended.submit(1));
            assertViolation(conflict, () -> ended.approve(1));
            assertViolation(conflict, () -> ended.reject(1));
            assertViolation(conflict, () -> ended.withdraw(1));
        }
    }

    @Test
    void approvalBeyondBalanceIsRejectedAndKeepsRequestPending() {
        LeaveLedger pending = LeaveLedger.open(ACCOUNT).grant(2).draft(1, UNIT, days(1, 3)).submit(1);

        assertViolation("LEAVE_BALANCE_INSUFFICIENT", () -> pending.approve(1));
        assertEquals(LeaveStatus.PENDING, status(pending, 1));
        assertEquals(2, pending.remainingDays());
    }

    @Test
    void exactBalanceCanBeUsedUpButNotTwice() {
        LeaveLedger ledger = LeaveLedger.open(ACCOUNT).grant(5)
                .draft(1, UNIT, days(1, 3)).submit(1)
                .draft(2, UNIT, days(10, 12)).submit(2);
        LeaveLedger first = ledger.approve(1);

        assertEquals(2, first.remainingDays());
        assertViolation("LEAVE_BALANCE_INSUFFICIENT", () -> first.approve(2));
        assertEquals(0, LeaveLedger.open(ACCOUNT).grant(3).draft(1, UNIT, days(1, 3)).submit(1).approve(1)
                .remainingDays());
    }

    @Test
    void periodOverlappingDraftPendingOrApprovedRequestIsRejected() {
        LeaveLedger draft = withDraft();
        LeaveLedger pending = draft.submit(1);
        LeaveLedger approved = pending.approve(1);

        for (LeaveLedger ledger : List.of(draft, pending, approved))
            assertViolation("LEAVE_PERIOD_OVERLAP", () -> ledger.draft(2, UNIT, days(3, 4)));
        assertEquals(LeaveStatus.DRAFT, status(draft.draft(2, UNIT, days(4, 5)), 2));
    }

    @Test
    void rejectedOrWithdrawnRequestNoLongerHoldsItsPeriod() {
        LeaveLedger pending = withDraft().submit(1);

        assertEquals(LeaveStatus.DRAFT, status(pending.reject(1).draft(2, UNIT, days(1, 3)), 2));
        assertEquals(LeaveStatus.DRAFT, status(pending.withdraw(1).draft(2, UNIT, days(2, 2)), 2));
    }

    @Test
    void requestVersionStartsAtOneAndGrowsWithEveryTransition() {
        LeaveLedger pending = withDraft().submit(1);

        assertEquals(1, withDraft().request(1).version());
        assertEquals(2, pending.request(1).version());
        assertEquals(3, pending.approve(1).request(1).version());
        assertEquals(3, pending.withdraw(1).request(1).version());
        assertViolation("LEAVE_INVALID", () -> new LeaveRequest(1, UNIT, days(1, 1), LeaveStatus.DRAFT, 0));
    }

    @Test
    void restoreChecksInvariants() {
        var first = new LeaveRequest(1, UNIT, days(1, 3), LeaveStatus.APPROVED, 3);
        var overlapping = new LeaveRequest(2, UNIT, days(2, 4), LeaveStatus.PENDING, 2);
        var withdrawn = new LeaveRequest(3, UNIT, days(2, 4), LeaveStatus.WITHDRAWN, 2);

        assertEquals(2, LeaveLedger.restore(ACCOUNT, 0, List.of(first, withdrawn), 3).requests().size());
        assertViolation("LEAVE_PERIOD_OVERLAP", () -> LeaveLedger.restore(ACCOUNT, 0, List.of(first, overlapping), 3));
        assertViolation("LEAVE_INVALID", () -> LeaveLedger.restore(ACCOUNT, -1, List.of(), 1));
        assertViolation("LEAVE_INVALID", () -> LeaveLedger.restore(ACCOUNT, 0, List.of(first, first), 1));
        assertViolation("LEAVE_INVALID", () -> LeaveLedger.restore(0, 0, List.of(), 1));
    }

    @Test
    void grantAddsDaysWithinBounds() {
        assertEquals(15, LeaveLedger.open(ACCOUNT).grant(10).grant(5).remainingDays());
        assertViolation("LEAVE_INVALID", () -> LeaveLedger.open(ACCOUNT).grant(0));
        assertViolation("LEAVE_INVALID", () -> LeaveLedger.open(ACCOUNT).grant(LeaveLedger.MAX_GRANT_DAYS + 1));
    }
}

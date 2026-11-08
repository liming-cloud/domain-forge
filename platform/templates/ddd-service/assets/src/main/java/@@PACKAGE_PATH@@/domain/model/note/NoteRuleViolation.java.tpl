package @@PACKAGE@@.domain.model.note;

import org.domainforge.shared.BusinessRuleViolation;

/**
 * 便笺聚合的规则违反：输入取值无效，提示是固定说明，可以对外返回。
 */
public final class NoteRuleViolation extends BusinessRuleViolation {
    private NoteRuleViolation(String message) {
        super(Kind.INVALID_REQUEST, "NOTE_INVALID", message);
    }

    /**
     * 便笺的字段取值违反规则（400）。
     *
     * @param message 固定说明
     * @return 规则违反
     */
    public static NoteRuleViolation invalid(String message) {
        return new NoteRuleViolation(message);
    }
}

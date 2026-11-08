package @@PACKAGE@@.application.command.note;

import org.domainforge.shared.BusinessRuleViolation;

/**
 * 便笺用例已判定的业务拒绝：带稳定错误码，由 web-support 按类别统一转为 Problem Details（404／409／422／400），
 * 入口不需要登记映射。
 */
public final class NoteRejected extends BusinessRuleViolation {
    private NoteRejected(Kind kind, String code, String message) {
        super(kind, code, message);
    }

    /**
     * 对象不存在或不在可见范围内（404）。
     */
    public static NoteRejected notFound(String code, String message) {
        return new NoteRejected(Kind.NOT_FOUND, code, message);
    }

    /**
     * 当前状态不允许该操作，或对象已被他人修改（409）。
     */
    public static NoteRejected conflict(String code, String message) {
        return new NoteRejected(Kind.CONFLICT, code, message);
    }

    /**
     * 输入取值无效（400）。
     */
    public static NoteRejected invalid(String code, String message) {
        return new NoteRejected(Kind.INVALID_REQUEST, code, message);
    }

    /**
     * 业务规则不满足（422）。
     */
    public static NoteRejected unprocessable(String code, String message) {
        return new NoteRejected(Kind.UNPROCESSABLE, code, message);
    }
}

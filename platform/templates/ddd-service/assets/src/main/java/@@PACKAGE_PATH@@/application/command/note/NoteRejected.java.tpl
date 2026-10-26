package @@PACKAGE@@.application.command.note;

/**
 * 已判定的便笺业务拒绝；入口映射为 HTTP 200 与稳定代码。
 */
public final class NoteRejected extends RuntimeException {
    private final String code;

    /**
     * 代码稳定，提示可安全展示。
     */
    public NoteRejected(String code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 客户端判断用的稳定代码。
     */
    public String code() {
        return code;
    }
}

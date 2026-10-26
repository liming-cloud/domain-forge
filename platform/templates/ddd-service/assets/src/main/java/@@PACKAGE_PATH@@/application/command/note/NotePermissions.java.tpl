package @@PACKAGE@@.application.command.note;

/**
 * 本服务在 IAM 权限目录中的命名空间与权限码；安装内容见 resources/iam/permissions.json。
 */
public final class NotePermissions {
    /**
     * 读取便笺，需要数据范围。
     */
    public static final String READ = "@@NAMESPACE@@:note:read";
    /**
     * 创建与修改便笺，需要数据范围。
     */
    public static final String WRITE = "@@NAMESPACE@@:note:write";

    private NotePermissions() {
    }
}

package @@PACKAGE@@.application.order;

/**
 * 本服务在 IAM 权限目录中的命名空间与权限码；安装内容见 resources/iam/permissions.json。
 */
public final class OrderPermissions {
    /**
     * 读取订单，需要数据范围。
     */
    public static final String READ = "@@NAMESPACE@@:order:read";
    /**
     * 创建与修改订单，需要数据范围。
     */
    public static final String WRITE = "@@NAMESPACE@@:order:write";

    private OrderPermissions() {
    }
}

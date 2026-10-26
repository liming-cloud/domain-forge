package @@PACKAGE@@.application.port.operation;

/**
 * 业务 ID 分配端口；实现使用雪花 ID，写入前确定。
 */
public interface Identifiers {
    /**
     * 下一个正数 ID。
     */
    long next();
}

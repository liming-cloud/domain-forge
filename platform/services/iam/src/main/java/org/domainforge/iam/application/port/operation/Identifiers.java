package org.domainforge.iam.application.port.operation;

/**
 * 业务实体 ID 的分配出口；实现使用雪花算法，ID 在写入前确定，不依赖数据库自增。
 */
public interface Identifiers {
    /**
     * 返回新的正数 ID；同一进程内单调递增，多实例依赖部署配置不同的 worker 标识。
     */
    long next();
}

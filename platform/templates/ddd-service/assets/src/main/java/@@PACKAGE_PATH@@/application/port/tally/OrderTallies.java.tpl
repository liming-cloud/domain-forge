package @@PACKAGE@@.application.port.tally;

/**
 * 本服务维护的订单统计（读模型）：由 @@SOURCE@@ 发布的 OrderSubmitted 事件累加，不是 @@SOURCE@@ 数据的副本。
 */
public interface OrderTallies {
    /**
     * 某账号的订单数加一。
     */
    void increment(long ownerId);

    /**
     * 某账号的订单数；没有记录为 0。
     */
    long count(long ownerId);
}

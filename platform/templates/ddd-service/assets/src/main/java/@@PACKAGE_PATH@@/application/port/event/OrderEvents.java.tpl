package @@PACKAGE@@.application.port.event;

import @@PACKAGE@@.application.contract.event.OrderSubmitted;

/**
 * 发布订单的集成事件。必须在业务事务内调用：事件与业务数据一起提交，业务回滚时事件一并消失。
 */
public interface OrderEvents {
    /**
     * 订单已提交。
     */
    void submitted(OrderSubmitted event);
}

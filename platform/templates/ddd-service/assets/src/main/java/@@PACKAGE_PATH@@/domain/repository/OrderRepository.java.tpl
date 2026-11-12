package @@PACKAGE@@.domain.repository;

import org.jspecify.annotations.Nullable;

import @@PACKAGE@@.domain.model.order.Order;

/**
 * 订单聚合仓储（订单行随聚合一起读写）；不存在返回 null，版本冲突不能当作成功。
 */
public interface OrderRepository {
    /**
     * 锁定订单直到事务结束。
     */
    @Nullable Order lock(long id);

    /**
     * 读取订单，不加锁（只用于读取前的范围判断）。
     */
    @Nullable Order find(long id);

    void insert(Order order);

    /**
     * 按原版本保存，零行返回 false。
     */
    boolean update(Order order, long expectedVersion);
}

package @@PACKAGE@@.domain.repository;

import org.jspecify.annotations.Nullable;

import @@PACKAGE@@.domain.model.order.Order;

/**
 * 订单聚合仓储（订单行随聚合一起读写）；不存在返回 null，版本冲突不能当作成功。
 */
public interface OrderRepository {
    /**
     * 锁定订单直到事务结束；只用于版本条件更新保证不了的约束（如凭证数上限），普通修改用 {@link #find}。
     */
    @Nullable Order lock(long id);

    /**
     * 读取订单，不加锁。
     */
    @Nullable Order find(long id);

    void insert(Order order);

    /**
     * 以修改前的版本为条件保存 changed；订单已被他人修改（零行）返回 false，由应用层判定为冲突。
     * original 是本次读取的订单，仓储据此只写有变化的部分。
     */
    boolean update(Order changed, Order original);
}

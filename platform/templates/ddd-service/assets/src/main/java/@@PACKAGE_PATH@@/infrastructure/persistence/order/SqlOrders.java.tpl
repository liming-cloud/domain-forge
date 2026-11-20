package @@PACKAGE@@.infrastructure.persistence.order;

import org.jspecify.annotations.Nullable;

import @@PACKAGE@@.domain.model.order.Money;
import @@PACKAGE@@.domain.model.order.Order;
import @@PACKAGE@@.domain.model.order.OrderLine;
import @@PACKAGE@@.domain.model.order.OrderStatus;
import @@PACKAGE@@.domain.repository.OrderRepository;
import org.springframework.stereotype.Repository;

/**
 * MyBatis 订单仓储：订单与订单行一起读写；保存时订单行有变化才整体替换，状态迁移只更新订单。
 */
@Repository
public class SqlOrders implements OrderRepository {
    private final OrderMapper mapper;

    /**
     * 绑定订单 Mapper。
     */
    public SqlOrders(OrderMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public @Nullable Order lock(long id) {
        return restore(mapper.lock(id));
    }

    @Override
    public @Nullable Order find(long id) {
        return restore(mapper.find(id));
    }

    @Override
    public void insert(Order order) {
        mapper.insert(row(order));
        insertLines(order);
    }

    @Override
    public boolean update(Order changed, Order original) {
        if (mapper.update(row(changed), original.version()) != 1) return false;
        if (changed.lines().equals(original.lines())) return true;
        mapper.deleteLines(changed.id());
        insertLines(changed);
        return true;
    }

    private @Nullable Order restore(@Nullable OrderRow row) {
        if (row == null) return null;
        var lines = mapper.lines(row.id()).stream()
                .map(line -> new OrderLine(line.id(), line.sku(), line.quantity(), new Money(line.unitPriceCents())))
                .toList();
        return Order.restore(row.id(), row.ownerId(), row.unitId(), OrderStatus.valueOf(row.status()), lines,
                row.version());
    }

    private void insertLines(Order order) {
        order.lines().forEach(line -> mapper.insertLine(order.id(),
                new OrderLineRow(line.id(), line.sku(), line.quantity(), line.unitPrice().cents())));
    }

    private static OrderRow row(Order order) {
        return new OrderRow(order.id(), order.ownerId(), order.unitId(), order.status().name(), order.version());
    }
}

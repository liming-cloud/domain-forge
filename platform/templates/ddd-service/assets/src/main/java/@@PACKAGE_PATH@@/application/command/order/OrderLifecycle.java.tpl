package @@PACKAGE@@.application.command.order;

import @@PACKAGE@@.application.contract.event.OrderSubmitted;
import @@PACKAGE@@.application.order.OrderAccess;
import @@PACKAGE@@.application.order.OrderPermissions;
import @@PACKAGE@@.application.order.OrderRejected;
import @@PACKAGE@@.application.port.access.DataScopes;
import @@PACKAGE@@.application.port.event.OrderEvents;
import @@PACKAGE@@.domain.event.OrderEvent;
import @@PACKAGE@@.domain.model.order.Order;
import @@PACKAGE@@.domain.repository.OrderRepository;
import org.domainforge.access.api.CurrentAccess;
import org.domainforge.shared.CurrentScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.UnaryOperator;

/**
 * 订单的状态迁移命令：提交、支付、取消。迁移是否合法由订单聚合判断，这里只做范围核对、版本核对与保存。
 *
 * <p>不加锁：读取时核对版本，保存时以原版本为条件更新；条件不满足说明订单已被他人修改，返回冲突。
 *
 * <p>保存成功后在同一事务内把聚合暂存的领域事件映射为集成事件发布（提交产生 {@link OrderSubmitted}）：事件与订单一起提交，
 * 回滚时一并消失。
 */
@Service
public class OrderLifecycle {
    private final CurrentAccess access;
    private final DataScopes scopes;
    private final OrderRepository orders;
    private final OrderEvents events;

    /**
     * 只依赖访问组件公开接口、本服务端口与仓储。
     */
    public OrderLifecycle(CurrentAccess access, DataScopes scopes, OrderRepository orders, OrderEvents events) {
        this.access = access;
        this.scopes = scopes;
        this.orders = orders;
        this.events = events;
    }

    /**
     * 提交草稿订单，并发布 {@link OrderSubmitted}。
     */
    @Transactional
    public OrderChange submit(long id, long version) {
        return result(change(id, version, Order::submit));
    }

    /**
     * 已提交的订单完成支付。
     */
    @Transactional
    public OrderChange pay(long id, long version) {
        return result(change(id, version, Order::pay));
    }

    /**
     * 取消草稿或已提交的订单。
     */
    @Transactional
    public OrderChange cancel(long id, long version) {
        return result(change(id, version, Order::cancel));
    }

    private Order change(long id, long version, UnaryOperator<Order> transition) {
        long account = access.current().accountId();
        CurrentScope scope = scopes.scope(OrderPermissions.WRITE);
        Order current = orders.find(id);
        if (current == null || !OrderAccess.within(current, account, scope))
            throw OrderRejected.notFound("ORDER_NOT_FOUND", "订单不存在");
        if (current.version() != version) throw OrderRejected.versionConflict();
        Order changed = transition.apply(current);
        if (!orders.update(changed, version)) throw OrderRejected.versionConflict();
        changed.events().forEach(this::publish);
        return changed.withoutEvents();
    }

    private void publish(OrderEvent event) {
        switch (event) {
            case @@PACKAGE@@.domain.event.OrderSubmitted fact ->
                    events.submitted(new OrderSubmitted(fact.orderId(), fact.ownerId(), fact.totalCents()));
        }
    }

    private static OrderChange result(Order order) {
        return new OrderChange(order.id(), order.version(), order.status().name());
    }
}

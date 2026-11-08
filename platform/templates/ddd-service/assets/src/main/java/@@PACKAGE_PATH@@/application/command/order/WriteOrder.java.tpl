package @@PACKAGE@@.application.command.order;

import @@PACKAGE@@.application.port.access.DataScopes;
import @@PACKAGE@@.application.port.operation.Identifiers;
import @@PACKAGE@@.domain.model.order.Money;
import @@PACKAGE@@.domain.model.order.Order;
import @@PACKAGE@@.domain.model.order.OrderLine;
import @@PACKAGE@@.domain.repository.OrderRepository;
import org.domainforge.access.api.AccessException;
import org.domainforge.access.api.AccessFailure;
import org.domainforge.access.api.CurrentAccess;
import org.domainforge.shared.CurrentScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 订单的编辑命令：权限码由入口声明检查，数据范围在用例内按目标对象核对，业务规则由订单聚合判断，写入在一个本地事务中完成。
 *
 * <p>IAM 范围在事务开始前读取，避免持锁等待远程调用；范围之后的变化只影响之后的新请求。
 */
@Service
public class WriteOrder {
    private final CurrentAccess access;
    private final DataScopes scopes;
    private final OrderRepository orders;
    private final Identifiers ids;

    /**
     * 只依赖访问组件公开接口、本服务端口与仓储。
     */
    public WriteOrder(CurrentAccess access, DataScopes scopes, OrderRepository orders, Identifiers ids) {
        this.access = access;
        this.scopes = scopes;
        this.orders = orders;
        this.ids = ids;
    }

    /**
     * 新建草稿订单：不归属组织时需要本人范围，归属组织时该组织须在写范围内。
     */
    @Transactional
    public OrderChange create(Long unitId, List<LineInput> lines) {
        long account = access.current().accountId();
        CurrentScope scope = scopes.scope(OrderPermissions.WRITE);
        if (!OrderAccess.creatable(unitId, scope)) throw new AccessException(AccessFailure.FORBIDDEN);
        Order order = Order.draft(ids.next(), account, unitId, lines.stream().map(this::line).toList());
        orders.insert(order);
        return new OrderChange(order.id(), order.version(), order.status().name());
    }

    /**
     * 给草稿订单增加一行：范围外的对象按不存在处理；版本不符为业务冲突。
     */
    @Transactional
    public OrderChange addLine(long id, long version, LineInput input) {
        long account = access.current().accountId();
        CurrentScope scope = scopes.scope(OrderPermissions.WRITE);
        Order current = orders.lock(id);
        if (current == null || !OrderAccess.within(current, account, scope))
            throw OrderRejected.notFound("ORDER_NOT_FOUND", "订单不存在");
        if (current.version() != version) throw OrderRejected.conflict("ORDER_CONFLICT", "订单已被修改，请刷新后重试");
        Order changed = current.addLine(line(input));
        if (!orders.update(changed, current.version()))
            throw OrderRejected.conflict("ORDER_CONFLICT", "订单已被修改，请刷新后重试");
        return new OrderChange(changed.id(), changed.version(), changed.status().name());
    }

    private OrderLine line(LineInput input) {
        return new OrderLine(ids.next(), input.sku(), input.quantity(), new Money(input.unitPriceCents()));
    }
}

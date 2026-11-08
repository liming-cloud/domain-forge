package @@PACKAGE@@.interfaces.http.order;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import @@PACKAGE@@.application.command.order.LineInput;
import @@PACKAGE@@.application.command.order.OrderLifecycle;
import @@PACKAGE@@.application.command.order.OrderPermissions;
import @@PACKAGE@@.application.command.order.WriteOrder;
import @@PACKAGE@@.application.query.order.ListVisibleOrders;
import @@PACKAGE@@.application.query.order.OrderQueries;
import org.domainforge.access.api.CurrentAccess;
import org.domainforge.idempotency.api.Idempotent;
import org.domainforge.web.api.PlatformJsonApi;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 订单示例入口：三条路径分别演示命令、纯查询与需要编排的查询。
 *
 * <p>权限码由原生注解在入口检查；数据范围由应用用例按对象核对。
 */
@RestController
@PlatformJsonApi
public class OrderController {
    private final WriteOrder writes;
    private final OrderLifecycle lifecycle;
    private final OrderQueries queries;
    private final ListVisibleOrders visible;
    private final CurrentAccess access;

    /**
     * 只依赖应用用例、查询合同与访问组件公开接口。
     */
    public OrderController(WriteOrder writes, OrderLifecycle lifecycle, OrderQueries queries,
                           ListVisibleOrders visible, CurrentAccess access) {
        this.writes = writes;
        this.lifecycle = lifecycle;
        this.queries = queries;
        this.visible = visible;
        this.access = access;
    }

    /**
     * 命令：在写范围内新建草稿订单。
     */
    @SaCheckPermission(OrderPermissions.WRITE)
    @PostMapping("/api/orders")
    public OrderChangeResponse create(@RequestBody CreateOrderRequest input) {
        return OrderChangeResponse.of(writes.create(input.unitId(),
                input.lines().stream().map(LineRequest::toInput).toList()));
    }

    /**
     * 命令：给写范围内的草稿订单增加一行，须携带读取时的版本。
     */
    @SaCheckPermission(OrderPermissions.WRITE)
    @PostMapping("/api/orders/{id}/lines")
    public OrderChangeResponse addLine(@PathVariable long id, @RequestBody AddLineRequest input) {
        return OrderChangeResponse.of(writes.addLine(id, input.expectedVersion(),
                new LineInput(input.sku(), input.quantity(), input.unitPriceCents())));
    }

    /**
     * 命令：提交订单并发布 OrderSubmitted。带 {@code Idempotency-Key} 头时，同一键的重复提交只执行一次并返回首次结果。
     */
    @SaCheckPermission(OrderPermissions.WRITE)
    @Idempotent
    @PostMapping("/api/orders/{id}/submit")
    public OrderChangeResponse submit(@PathVariable long id, @RequestBody OrderVersionRequest input) {
        return OrderChangeResponse.of(lifecycle.submit(id, input.expectedVersion()));
    }

    /**
     * 命令：已提交的订单完成支付。
     */
    @SaCheckPermission(OrderPermissions.WRITE)
    @PostMapping("/api/orders/{id}/pay")
    public OrderChangeResponse pay(@PathVariable long id, @RequestBody OrderVersionRequest input) {
        return OrderChangeResponse.of(lifecycle.pay(id, input.expectedVersion()));
    }

    /**
     * 命令：取消草稿或已提交的订单。
     */
    @SaCheckPermission(OrderPermissions.WRITE)
    @PostMapping("/api/orders/{id}/cancel")
    public OrderChangeResponse cancel(@PathVariable long id, @RequestBody OrderVersionRequest input) {
        return OrderChangeResponse.of(lifecycle.cancel(id, input.expectedVersion()));
    }

    /**
     * 纯查询：本人创建的订单，只需登录。
     */
    @SaCheckLogin
    @GetMapping("/api/me/orders")
    public List<OrderResponse> mine(
            @RequestParam(defaultValue = "0") long after, @RequestParam(defaultValue = "20") int limit) {
        return queries.owned(access.current().accountId(), after, limit).stream()
                .map(OrderResponse::of).toList();
    }

    /**
     * 需要编排的查询：按 IAM 读范围过滤。
     */
    @SaCheckPermission(OrderPermissions.READ)
    @GetMapping("/api/orders")
    public List<OrderResponse> list(
            @RequestParam(defaultValue = "0") long after, @RequestParam(defaultValue = "20") int limit) {
        return visible.list(after, limit).stream().map(OrderResponse::of).toList();
    }
}

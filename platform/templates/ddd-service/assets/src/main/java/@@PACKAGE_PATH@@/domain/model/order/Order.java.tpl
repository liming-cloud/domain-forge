package @@PACKAGE@@.domain.model.order;

import org.jspecify.annotations.Nullable;

import @@PACKAGE@@.domain.event.OrderSubmitted;
import org.jmolecules.ddd.annotation.AggregateRoot;
import org.jmolecules.ddd.annotation.Identity;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 示例聚合：一张订单属于下单人，可选归属一个组织；订单行是聚合内部的子实体，总额等跨行约束由聚合维护。
 *
 * <p>生命周期：草稿 → 已提交 → 已支付；草稿与已提交可取消，其余转换一律拒绝。每次变化返回新的订单，原订单不变；
 * 提交产生的领域事件随返回的订单暂存，由应用层取出。数据范围（谁能读写）是授权决定，不在领域内。
 */
@AggregateRoot
public final class Order {
    /**
     * 一张订单最多的行数。
     */
    public static final int MAX_LINES = 20;
    /**
     * 订单总额上限（100 万元）。
     */
    public static final Money MAX_TOTAL = new Money(100_000_000L);

    @Identity
    private final long id;
    private final long ownerId;
    private final @Nullable Long unitId;
    private final OrderStatus status;
    private final List<OrderLine> lines;
    private final long version;
    private final OrderSubmitted submitted;

    private Order(long id, long ownerId, @Nullable Long unitId, OrderStatus status, List<OrderLine> lines, long version,
                  OrderSubmitted submitted) {
        if (id <= 0 || ownerId <= 0 || version < 1 || (unitId != null && unitId <= 0) || status == null)
            throw OrderRuleViolation.invalid("订单身份、归属、状态或版本无效");
        this.id = id;
        this.ownerId = ownerId;
        this.unitId = unitId;
        this.status = status;
        this.lines = List.copyOf(lines);
        this.version = version;
        this.submitted = submitted;
        checkLines(this.lines);
    }

    private static void checkLines(List<OrderLine> lines) {
        if (lines.size() > MAX_LINES) throw OrderRuleViolation.linesFull();
        if (lines.stream().map(OrderLine::sku).distinct().count() != lines.size())
            throw OrderRuleViolation.duplicateSku();
        Money total = Money.zero();
        for (OrderLine line : lines) {
            total = total.plus(line.subtotal());
            if (total.exceeds(MAX_TOTAL)) throw OrderRuleViolation.totalExceeded();
        }
    }

    /**
     * 下单人在给定组织（或不归属组织）下新建草稿订单，可带初始订单行。
     */
    public static Order draft(long id, long ownerId, @Nullable Long unitId, List<OrderLine> lines) {
        return new Order(id, ownerId, unitId, OrderStatus.DRAFT, lines, 1, null);
    }

    /**
     * 从存储恢复；恢复也校验全部不变式。
     */
    public static Order restore(long id, long ownerId, @Nullable Long unitId, OrderStatus status, List<OrderLine> lines,
                                long version) {
        return new Order(id, ownerId, unitId, status, lines, version, null);
    }

    /**
     * 草稿订单增加一行；行数、重复商品与总额由聚合校验。
     */
    public Order addLine(OrderLine line) {
        require(OrderStatus.DRAFT, "修改订单行");
        List<OrderLine> next = new ArrayList<>(lines);
        next.add(line);
        return new Order(id, ownerId, unitId, status, next, version + 1, null);
    }

    /**
     * 提交草稿订单：至少有一行；产生 {@link OrderSubmitted}。
     */
    public Order submit() {
        require(OrderStatus.DRAFT, "提交");
        if (lines.isEmpty()) throw OrderRuleViolation.empty();
        return new Order(id, ownerId, unitId, OrderStatus.SUBMITTED, lines, version + 1,
                new OrderSubmitted(id, ownerId, total().cents()));
    }

    /**
     * 已提交的订单完成支付。
     */
    public Order pay() {
        require(OrderStatus.SUBMITTED, "支付");
        return new Order(id, ownerId, unitId, OrderStatus.PAID, lines, version + 1, null);
    }

    /**
     * 草稿或已提交的订单可以取消。
     */
    public Order cancel() {
        if (status != OrderStatus.DRAFT && status != OrderStatus.SUBMITTED)
            throw OrderRuleViolation.illegalTransition("取消");
        return new Order(id, ownerId, unitId, OrderStatus.CANCELLED, lines, version + 1, null);
    }

    private void require(OrderStatus expected, String action) {
        if (status != expected) throw OrderRuleViolation.illegalTransition(action);
    }

    /**
     * 订单总额：全部订单行小计之和。
     */
    public Money total() {
        return lines.stream().map(OrderLine::subtotal).reduce(Money.zero(), Money::plus);
    }

    /**
     * 本次变化产生的提交事件；只有 {@link #submit()} 返回的订单带有，从存储恢复的订单没有。
     */
    public Optional<OrderSubmitted> submitted() {
        return Optional.ofNullable(submitted);
    }

    /**
     * 订单身份。
     */
    public long id() {
        return id;
    }

    /**
     * 下单人账号 ID。
     */
    public long ownerId() {
        return ownerId;
    }

    /**
     * 所属组织 ID；不归属组织为 null。
     */
    public @Nullable Long unitId() {
        return unitId;
    }

    /**
     * 当前状态。
     */
    public OrderStatus status() {
        return status;
    }

    /**
     * 订单行（不可变视图）。
     */
    public List<OrderLine> lines() {
        return lines;
    }

    /**
     * 乐观锁版本，每次变化加一。
     */
    public long version() {
        return version;
    }
}

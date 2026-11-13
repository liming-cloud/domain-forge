package @@PACKAGE@@.domain.event;

/**
 * 订单聚合产生的领域事件；密封接口让应用层用穷尽的 {@code switch} 映射每一种事件，新增事件时编译器指出漏处理的地方。
 */
public sealed interface OrderEvent permits OrderSubmitted {
}

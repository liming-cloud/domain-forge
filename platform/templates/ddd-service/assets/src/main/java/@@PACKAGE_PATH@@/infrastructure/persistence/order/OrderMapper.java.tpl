package @@PACKAGE@@.infrastructure.persistence.order;

import org.jspecify.annotations.Nullable;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 订单持久化 SQL，写在 mapper/order/OrderMapper.xml。
 */
@org.apache.ibatis.annotations.Mapper
public interface OrderMapper {
    @Nullable OrderRow lock(long id);

    @Nullable OrderRow find(long id);

    List<OrderLineRow> lines(long orderId);

    int insert(OrderRow row);

    int insertLine(@Param("orderId") long orderId, @Param("line") OrderLineRow line);

    int update(@Param("row") OrderRow row, @Param("expected") long expected);

    int deleteLines(long orderId);
}

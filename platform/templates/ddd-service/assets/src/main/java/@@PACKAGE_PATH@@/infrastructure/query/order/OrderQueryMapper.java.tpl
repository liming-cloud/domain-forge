package @@PACKAGE@@.infrastructure.query.order;

import @@PACKAGE@@.application.query.order.OrderFilter;
import @@PACKAGE@@.application.query.order.OrderView;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 订单查询 SQL；范围条件全部参数绑定。
 */
@org.apache.ibatis.annotations.Mapper
public interface OrderQueryMapper {
    List<OrderView> owned(@Param("owner") long owner, @Param("after") long after, @Param("limit") int limit);

    List<OrderView> visible(@Param("filter") OrderFilter filter);
}

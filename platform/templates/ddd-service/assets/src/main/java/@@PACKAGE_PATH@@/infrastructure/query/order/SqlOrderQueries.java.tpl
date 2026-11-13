package @@PACKAGE@@.infrastructure.query.order;

import @@PACKAGE@@.application.order.OrderRejected;
import @@PACKAGE@@.application.query.order.OrderFilter;
import @@PACKAGE@@.application.query.order.OrderQueries;
import @@PACKAGE@@.application.query.order.OrderView;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 订单查询实现。
 */
@Repository
public class SqlOrderQueries implements OrderQueries {
    private final OrderQueryMapper mapper;

    /**
     * 只绑定查询 Mapper。
     */
    public SqlOrderQueries(OrderQueryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<OrderView> owned(long ownerId, long after, int limit) {
        if (after < 0 || limit < 1 || limit > 100) throw OrderRejected.invalid("ORDER_INVALID", "分页游标须非负且大小须为1至100");
        return mapper.owned(ownerId, after, limit);
    }

    @Override
    public List<OrderView> visible(OrderFilter filter) {
        return mapper.visible(filter);
    }
}

package @@PACKAGE@@.infrastructure.persistence.tally;

import @@PACKAGE@@.application.port.tally.OrderTallies;
import org.springframework.stereotype.Component;

/**
 * 订单统计的 PostgreSQL 实现。
 */
@Component
public class SqlOrderTallies implements OrderTallies {
    private final OrderTallyMapper mapper;

    /**
     * 只依赖本服务的 Mapper。
     */
    public SqlOrderTallies(OrderTallyMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void increment(long ownerId) {
        mapper.increment(ownerId);
    }

    @Override
    public long count(long ownerId) {
        Long count = mapper.count(ownerId);
        return count == null ? 0 : count;
    }
}

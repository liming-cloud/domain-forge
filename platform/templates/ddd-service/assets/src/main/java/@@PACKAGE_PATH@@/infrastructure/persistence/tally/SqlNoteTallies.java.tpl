package @@PACKAGE@@.infrastructure.persistence.tally;

import @@PACKAGE@@.application.port.tally.NoteTallies;
import org.springframework.stereotype.Component;

/**
 * 便笺统计的 PostgreSQL 实现。
 */
@Component
public class SqlNoteTallies implements NoteTallies {
    private final NoteTallyMapper mapper;

    /**
     * 只依赖本服务的 Mapper。
     */
    public SqlNoteTallies(NoteTallyMapper mapper) {
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

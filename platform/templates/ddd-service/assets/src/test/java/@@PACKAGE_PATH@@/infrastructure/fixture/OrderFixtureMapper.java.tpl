package @@PACKAGE@@.infrastructure.fixture;

import org.apache.ibatis.annotations.Mapper;

import java.util.Map;

/**
 * 集成测试专用：重置数据并观察真实表状态（SQL 在 mapper/fixture/OrderFixtureMapper.xml）。
 */
@Mapper
public interface OrderFixtureMapper {
    /**
     * 清空订单、文件与 Outbox 表。
     */
    void reset();

    int outboxCount();

    /**
     * 唯一一条 Outbox 记录的主题、类型、键与载荷（列名为键）。
     */
    Map<String, Object> onlyOutboxEvent();

    int lines(long orderId);

    /**
     * 该文件记录已标记删除时为 1。
     */
    int deletedFiles(String id);
}

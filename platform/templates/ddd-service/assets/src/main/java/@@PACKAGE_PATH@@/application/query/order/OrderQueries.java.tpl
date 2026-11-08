package @@PACKAGE@@.application.query.order;

import java.util.List;

/**
 * 订单查询合同，实现直接使用本服务 SQL 投影；按 ID 升序键分页。
 */
public interface OrderQueries {
    /**
     * 本人创建的订单；只需登录，是不经过编排的纯查询。
     */
    List<OrderView> owned(long ownerId, long after, int limit);

    /**
     * 按可信范围过滤的订单。
     */
    List<OrderView> visible(OrderFilter filter);
}

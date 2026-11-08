package @@PACKAGE@@.application.query.tally;

import @@PACKAGE@@.application.port.tally.OrderTallies;
import org.springframework.stereotype.Service;

/**
 * 当前账号在 @@SOURCE@@ 中提交的订单数（最终一致：事件到达前不计入）。
 */
@Service
public class OrderTallyQueries {
    private final OrderTallies tallies;

    /**
     * 只依赖统计端口。
     */
    public OrderTallyQueries(OrderTallies tallies) {
        this.tallies = tallies;
    }

    /**
     * 某账号的订单数。
     */
    public long count(long ownerId) {
        return tallies.count(ownerId);
    }
}

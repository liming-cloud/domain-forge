package @@PACKAGE@@.application.command.tally;

import @@PACKAGE@@.application.port.tally.OrderTallies;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 消费示例的命令：@@SOURCE@@ 的一个订单已提交，累加下单人的订单数。
 *
 * <p>由消息组件在本地事务内调用，去重记录与累加一起提交，同一事件只累加一次。
 */
@Service
public class RecordOrderSubmitted {
    private final OrderTallies tallies;

    /**
     * 只依赖统计端口。
     */
    public RecordOrderSubmitted(OrderTallies tallies) {
        this.tallies = tallies;
    }

    /**
     * 加入调用方（消息组件）的事务。
     */
    @Transactional
    public void record(long ownerId) {
        tallies.increment(ownerId);
    }
}

package org.domainforge.messaging.internal;

import org.domainforge.messaging.api.EventPublisher;
import org.domainforge.messaging.api.OutgoingEvent;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/**
 * 在当前事务内写入 Outbox；提交后唤醒中继尽快发送。唤醒丢失（进程在提交后退出）时由中继的定时扫描恢复。
 */
public final class OutboxPublisher implements EventPublisher {
    private final OutboxMapper outbox;
    private final Runnable wake;

    /**
     * 写入与唤醒分离，未启用中继时唤醒为空操作。
     *
     * @param outbox Outbox 写入
     * @param wake   提交后唤醒中继
     */
    public OutboxPublisher(OutboxMapper outbox, Runnable wake) {
        this.outbox = outbox;
        this.wake = wake;
    }

    @Override
    public String publish(OutgoingEvent event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("集成事件必须在业务事务内发布");
        String id = UUID.randomUUID().toString();
        outbox.insert(event, id);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                wake.run();
            }
        });
        return id;
    }
}

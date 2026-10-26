package org.springframework.transaction.support;

import org.springframework.transaction.TransactionStatus;

/**
 * 测试替身：与 Spring 同名的事务回调。
 */
@FunctionalInterface
public interface TransactionCallback<T> {
    /**
     * 在事务内执行。
     */
    T doInTransaction(TransactionStatus status);
}

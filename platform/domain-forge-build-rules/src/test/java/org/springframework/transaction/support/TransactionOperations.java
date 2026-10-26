package org.springframework.transaction.support;

/**
 * 测试替身：与 Spring 同名的编程式事务接口。
 */
public interface TransactionOperations {
    /**
     * 在事务内执行回调。
     */
    <T> T execute(TransactionCallback<T> action);
}

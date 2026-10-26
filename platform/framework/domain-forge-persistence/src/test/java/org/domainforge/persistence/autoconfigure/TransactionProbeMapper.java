package org.domainforge.persistence.autoconfigure;

/**
 * 真实事务验证使用 XML SQL，不把测试表带入模板或生产迁移。
 */
public interface TransactionProbeMapper {
    void insert(String value);

    int count();

    String transactionId();

    int schemaExists(String name);
}

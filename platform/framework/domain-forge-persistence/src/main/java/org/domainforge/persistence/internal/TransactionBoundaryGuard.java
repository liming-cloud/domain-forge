package org.domainforge.persistence.internal;

import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;

/**
 * 跨数据源误用检查：有进行中的事务、但事务没有绑定本数据源时拒绝执行 SQL。
 *
 * <p>否则在另一个数据源的事务里调用本数据源的 Mapper 会静默地以自动提交执行，看起来像在同一事务中、
 * 实际不随它回滚。需要同时写两个库时，分别在各自的事务中执行，并按跨资源处理（P-14）。
 */
@Intercepts({
        @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}),
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class, CacheKey.class,
                        BoundSql.class})})
public final class TransactionBoundaryGuard implements Interceptor {
    private final String name;
    private final DataSource dataSource;

    /**
     * 绑定一个数据源；名称只用于错误信息。
     */
    public TransactionBoundaryGuard(String name, DataSource dataSource) {
        this.name = name;
        this.dataSource = dataSource;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.hasResource(dataSource)) {
            String statement = ((MappedStatement) invocation.getArgs()[0]).getId();
            throw new IllegalStateException("在其他数据源的事务中访问数据源 " + name + "（" + statement
                    + "）：跨数据源没有原子性，请在该数据源自己的事务中执行");
        }
        return invocation.proceed();
    }
}

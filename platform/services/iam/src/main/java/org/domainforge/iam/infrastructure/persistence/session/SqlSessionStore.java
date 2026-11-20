package org.domainforge.iam.infrastructure.persistence.session;

import org.domainforge.iam.application.port.IamUnavailableException;
import org.domainforge.iam.application.port.authentication.CurrentIdentity;
import org.domainforge.iam.application.port.authentication.SessionGrant;
import org.domainforge.iam.application.port.authentication.SessionStore;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 身份查询是一条带语句超时的 SQL：不在命令事务中时自动提交执行，参加当前命令事务时保持 READ_COMMITTED 新语句语义。
 *
 * <p>不为它单独开事务：每个受保护请求都要读一次身份，事务的开启、隔离级别读取与提交会让一次查询变成四次数据库往返。
 */
@Repository
public class SqlSessionStore implements SessionStore {
    private final SessionMapper mapper;

    /**
     * 共用唯一数据源，不为了认证偷偷建立额外数据库连接池。
     */
    public SqlSessionStore(SessionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insert(SessionGrant grant) {
        mapper.insert(
                grant.tokenDigest(),
                grant.accountId(),
                grant.credentialVersion(),
                grant.expiresAt());
    }

    /**
     * 每次都运行新SQL，不缓存任何身份结果。
     */
    @Override
    public @Nullable CurrentIdentity current(String digest) {
        Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        if (isolation != null && isolation != TransactionDefinition.ISOLATION_READ_COMMITTED) {
            throw new IamUnavailableException("身份校验不接受旧事务快照", null);
        }
        IdentityRow row = mapper.current(digest);
        return row == null ? null : row.toIdentity(digest);
    }

    @Override
    public void revoke(String digest) {
        mapper.revoke(digest);
    }
}

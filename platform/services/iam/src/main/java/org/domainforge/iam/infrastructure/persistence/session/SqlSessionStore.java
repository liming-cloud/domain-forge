package org.domainforge.iam.infrastructure.persistence.session;

import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.IamFailure;
import org.domainforge.iam.application.port.authentication.CurrentIdentity;
import org.domainforge.iam.application.port.authentication.SessionGrant;
import org.domainforge.iam.application.port.authentication.SessionStore;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 身份查询在短事务内设置语句预算，参加当前命令事务时保持READ_COMMITTED新语句语义。
 */
@Repository
public class SqlSessionStore implements SessionStore {
    private final SessionMapper mapper;
    private final TransactionTemplate read;

    /**
     * 共用唯一数据源，不为了认证偷偷建立额外数据库连接池。
     */
    public SqlSessionStore(SessionMapper mapper, PlatformTransactionManager manager) {
        this.mapper = mapper;
        read = new TransactionTemplate(manager);
        read.setTimeout(1);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
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
     * 每次都运行新SQL；预算设置不缓存任何身份结果。
     */
    @Override
    public CurrentIdentity current(String digest) {
        Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        if (isolation != null && isolation != TransactionDefinition.ISOLATION_READ_COMMITTED) {
            throw new IamException(IamFailure.UNAVAILABLE, "身份校验不接受旧事务快照");
        }
        return read.execute(
                status -> {
                    mapper.budget();
                    IdentityRow row = mapper.current(digest);
                    return row == null ? null : row.toIdentity(digest);
                });
    }

    @Override
    public void revoke(String digest) {
        mapper.revoke(digest);
    }
}

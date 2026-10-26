package org.domainforge.iam.application.command.authentication;

import org.domainforge.iam.application.authorization.CurrentAuthorization;
import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.IamFailure;
import org.domainforge.iam.application.port.audit.AuditEvent;
import org.domainforge.iam.application.port.audit.AuditTrail;
import org.domainforge.iam.application.port.authentication.CurrentIdentity;
import org.domainforge.iam.application.port.authentication.NativeSessions;
import org.domainforge.iam.application.port.authentication.SecretOperations;
import org.domainforge.iam.application.port.authentication.SessionStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 当前会话退出先提交 SQL 撤销与审计，再尽力清理 Redis；同账号其他会话保持有效。
 */
@Service
public final class Logout {
    private final TransactionOperations transactions;
    private final CurrentAuthorization authorization;
    private final SecretOperations secrets;
    private final SessionStore sessions;
    private final NativeSessions nativeSessions;
    private final AuditTrail audit;

    /**
     * 会话清理不承担安全生效，SQL 许可始终是必要条件。
     */
    public Logout(
            TransactionOperations transactions,
            CurrentAuthorization authorization,
            SecretOperations secrets,
            SessionStore sessions,
            NativeSessions nativeSessions,
            AuditTrail audit) {
        this.transactions = transactions;
        this.authorization = authorization;
        this.secrets = secrets;
        this.sessions = sessions;
        this.nativeSessions = nativeSessions;
        this.audit = audit;
    }

    /**
     * token 必须是入口原生解析的当前令牌，不能由请求体指定他人令牌；提交后清理失败只计数，不改变结果。
     */
    public void execute(CurrentIdentity identity, String token) {
        if (token == null || !identity.tokenDigest().equals(secrets.digest(token)))
            throw new IamException(IamFailure.INVALID_REQUEST, "退出输入无效");
        transactions.executeWithoutResult(status -> {
            var actor = authorization.require(identity.tokenDigest(), null);
            sessions.revoke(identity.tokenDigest());
            audit.record(new AuditEvent(Long.toString(actor.accountId()), actor.accountId(), "session.logout"));
        });
        nativeSessions.cleanup(token);
    }
}

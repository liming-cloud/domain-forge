package org.domainforge.iam.application.command.authentication;

import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.IamFailure;
import org.domainforge.iam.application.port.authentication.*;
import org.domainforge.iam.application.port.operation.DatabaseTime;
import org.domainforge.iam.application.port.operation.Transactions;
import org.domainforge.iam.domain.model.Account;
import org.domainforge.iam.domain.repository.AccountRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 登录先预占防护再校验密码；原生令牌与SQL许可不伪装成分布式原子事务。
 */
@Service
public final class Login {
    private final AccountRepository accounts;
    private final Passwords passwords;
    private final LoginProtection protection;
    private final NativeSessions nativeSessions;
    private final SessionStore sessions;
    private final SecretOperations secrets;
    private final Transactions transactions;
    private final DatabaseTime clock;

    /**
     * 哈希在事务之外，数据库短事务只重新核对状态并登记许可。
     */
    public Login(
            AccountRepository accounts,
            Passwords passwords,
            LoginProtection protection,
            NativeSessions nativeSessions,
            SessionStore sessions,
            SecretOperations secrets,
            Transactions transactions,
            DatabaseTime clock) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.protection = protection;
        this.nativeSessions = nativeSessions;
        this.sessions = sessions;
        this.secrets = secrets;
        this.transactions = transactions;
        this.clock = clock;
    }

    private static IamException rejected() {
        return new IamException(IamFailure.UNAUTHENTICATED, "登录凭据无效");
    }

    /**
     * 不存在、禁用和错误密码给出同一认证失败；直接地址由可信传输入口提供。
     */
    public LoginResult execute(String loginName, String password, String directAddress) {
        String name = Account.normalizeLoginName(loginName);
        var reservation = protection.reserve(name, directAddress);
        Account verified = accounts.findByLoginName(name);
        boolean matches =
                passwords.matches(password, verified == null ? null : verified.credentialDigest());
        if (!matches || verified == null || !verified.enabled()) throw rejected();
        String token = nativeSessions.issue(verified.id());
        try {
            transactions.execute(
                    () -> {
                        Account current = accounts.lock(verified.id());
                        if (current == null
                                || !current.enabled()
                                || current.credentialVersion() != verified.credentialVersion()) throw rejected();
                        sessions.insert(
                                new SessionGrant(
                                        secrets.digest(token),
                                        current.id(),
                                        current.credentialVersion(),
                                        clock.now().plus(Duration.ofHours(8)),
                                        false));
                        return null;
                    });
            protection.succeeded(reservation);
            return new LoginResult(token, Duration.ofHours(8).toSeconds());
        } catch (RuntimeException failure) {
            // 失败清理只是资源收敛；缺少SQL许可或版本不符本身已禁止授权。
            try {
                nativeSessions.revoke(token);
            } catch (RuntimeException ignored) {
                /* 原始失败仍返回调用方。 */
            }
            throw failure;
        }
    }
}

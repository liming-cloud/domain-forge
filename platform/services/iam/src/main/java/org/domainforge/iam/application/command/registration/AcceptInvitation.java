package org.domainforge.iam.application.command.registration;

import org.domainforge.iam.application.command.CommandResult;
import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.IamFailure;
import org.domainforge.iam.application.port.audit.AuditEvent;
import org.domainforge.iam.application.port.audit.AuditTrail;
import org.domainforge.iam.application.port.authentication.Passwords;
import org.domainforge.iam.application.port.authentication.SecretOperations;
import org.domainforge.iam.application.port.operation.DatabaseTime;
import org.domainforge.iam.application.port.operation.Identifiers;
import org.domainforge.iam.domain.model.Account;
import org.domainforge.iam.domain.model.RegistrationInvitation;
import org.domainforge.iam.domain.repository.AccountRepository;
import org.domainforge.iam.domain.repository.InvitationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 邀请消费与新账号创建原子提交；同一邀请只能成功一次，重复提交返回冲突。
 */
@Service
public final class AcceptInvitation {
    private final TransactionOperations transactions;
    private final DatabaseTime clock;
    private final AccountRepository accounts;
    private final InvitationRepository invitations;
    private final Identifiers identifiers;
    private final Passwords passwords;
    private final SecretOperations secrets;
    private final AuditTrail audit;

    /**
     * 只复用本上下文仓储及密码出口，不依赖 HTTP 或持久化对象。
     */
    public AcceptInvitation(
            TransactionOperations transactions,
            DatabaseTime clock,
            AccountRepository accounts,
            InvitationRepository invitations,
            Identifiers identifiers,
            Passwords passwords,
            SecretOperations secrets,
            AuditTrail audit) {
        this.transactions = transactions;
        this.clock = clock;
        this.accounts = accounts;
        this.invitations = invitations;
        this.identifiers = identifiers;
        this.passwords = passwords;
        this.secrets = secrets;
        this.audit = audit;
    }

    /**
     * 注册密码不做规范化；密码摘要在事务外计算，邀请消费、建号与审计在同一事务提交。
     */
    public CommandResult execute(String secret, String loginName, String password) {
        if (secret == null || !secret.matches("[A-Za-z0-9_-]{43}"))
            throw new IamException(IamFailure.INVALID_REQUEST, "注册输入无效");
        String name = Account.normalizeLoginName(loginName);
        String invitationDigest = secrets.digest(secret);
        String digest = passwords.encode(password);
        return transactions.execute(status -> register(invitationDigest, name, digest));
    }

    /**
     * 锁定邀请后消费；任一步失败都同时回滚账号、邀请和审计。
     */
    private CommandResult register(String invitationDigest, String name, String digest) {
        RegistrationInvitation invitation = invitations.lockByDigest(invitationDigest);
        if (invitation == null || !invitation.loginName().equals(name))
            throw new IamException(IamFailure.CONFLICT, "邀请无效或已失效");
        Account account = Account.register(identifiers.next(), name, digest);
        RegistrationInvitation consumed;
        try {
            consumed = invitation.consume(account.id(), clock.now());
        } catch (IllegalStateException e) {
            throw new IamException(IamFailure.CONFLICT, "邀请无效或已失效");
        }
        accounts.insert(account);
        invitations.finish(consumed);
        audit.record(new AuditEvent("invitation-holder", account.id(), "invitation.accept"));
        return new CommandResult(account.id(), account.version());
    }
}

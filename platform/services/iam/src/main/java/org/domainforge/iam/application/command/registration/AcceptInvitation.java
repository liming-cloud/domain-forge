package org.domainforge.iam.application.command.registration;

import org.domainforge.iam.application.command.CommandResult;
import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.audit.AuditEvent;
import org.domainforge.iam.application.port.audit.AuditTrail;
import org.domainforge.iam.application.port.authentication.Passwords;
import org.domainforge.iam.application.port.authentication.SecretOperations;
import org.domainforge.iam.application.port.operation.DatabaseTime;
import org.domainforge.iam.application.port.operation.Identifiers;
import org.domainforge.iam.domain.IamRuleViolation;
import org.domainforge.iam.domain.model.account.Account;
import org.domainforge.iam.domain.model.invitation.RegistrationInvitation;
import org.domainforge.iam.domain.model.shared.LoginName;
import org.domainforge.iam.domain.repository.AccountRepository;
import org.domainforge.iam.domain.repository.InvitationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 注册只新建账号一个聚合；邀请是一次性凭据，使用事实记录在账号上，同一邀请只能成功一次，重复提交返回冲突。
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
     * 注册密码不做规范化；密码摘要在事务外计算，建号与审计在同一事务提交。
     */
    public CommandResult execute(String secret, String loginName, String password) {
        if (secret == null || !secret.matches("[A-Za-z0-9_-]{43}"))
            throw IamException.invalid("注册输入无效");
        String name = new LoginName(loginName).value();
        String invitationDigest = secrets.digest(secret);
        String digest = passwords.encode(password);
        return transactions.execute(status -> register(invitationDigest, name, digest));
    }

    /**
     * 锁定邀请（与重发互斥）后校验并建号；邀请本身不被修改，任一步失败都回滚账号与审计。
     */
    private CommandResult register(String invitationDigest, String name, String digest) {
        RegistrationInvitation invitation = invitations.lockByDigest(invitationDigest);
        if (invitation == null || !invitation.loginName().equals(name) || accounts.invitationUsed(invitationDigest))
            throw IamRuleViolation.conflict("INVITATION_UNUSABLE", "邀请无效或已失效");
        invitation.requireUsable(clock.now());
        Account account = Account.register(identifiers.next(), name, digest);
        accounts.insertInvited(account, invitationDigest);
        audit.record(new AuditEvent("invitation-holder", account.id(), "invitation.accept"));
        return new CommandResult(account.id(), account.version());
    }
}

package org.domainforge.iam.application.command.registration;

import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.IamFailure;
import org.domainforge.iam.application.port.audit.AuditEvent;
import org.domainforge.iam.application.port.audit.AuditTrail;
import org.domainforge.iam.application.port.authentication.Passwords;
import org.domainforge.iam.application.port.authentication.SecretOperations;
import org.domainforge.iam.application.port.operation.CommandReceipts;
import org.domainforge.iam.application.port.operation.CommandResult;
import org.domainforge.iam.application.port.operation.DatabaseTime;
import org.domainforge.iam.application.port.operation.Transactions;
import org.domainforge.iam.domain.model.Account;
import org.domainforge.iam.domain.model.RegistrationInvitation;
import org.domainforge.iam.domain.repository.AccountRepository;
import org.domainforge.iam.domain.repository.InvitationRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 邀请消费与新账号创建原子提交，匿名重放依据是邀请秘密而非自报账号ID。
 */
@Service
public final class AcceptInvitation {
    private final Transactions transactions;
    private final DatabaseTime clock;
    private final AccountRepository accounts;
    private final InvitationRepository invitations;
    private final CommandReceipts receipts;
    private final Passwords passwords;
    private final SecretOperations secrets;
    private final AuditTrail audit;

    /**
     * 只复用本上下文真实仓储及密码出口，不依赖HTTP或持久化对象。
     */
    public AcceptInvitation(
            Transactions transactions,
            DatabaseTime clock,
            AccountRepository accounts,
            InvitationRepository invitations,
            CommandReceipts receipts,
            Passwords passwords,
            SecretOperations secrets,
            AuditTrail audit) {
        this.transactions = transactions;
        this.clock = clock;
        this.accounts = accounts;
        this.invitations = invitations;
        this.receipts = receipts;
        this.passwords = passwords;
        this.secrets = secrets;
        this.audit = audit;
    }

    /**
     * 注册密码不做规范化；同操作ID的不同输入拒绝，成功重试不会新建第二账号。
     */
    public CommandResult execute(UUID operation, String secret, String loginName, String password) {
        if (operation == null || secret == null || !secret.matches("[A-Za-z0-9_-]{43}")) {
            throw new IamException(IamFailure.INVALID_REQUEST, "注册输入无效");
        }
        String name = Account.normalizeLoginName(loginName);
        String invitationDigest = secrets.digest(secret);
        String fingerprint = secrets.fingerprint("invitation.accept", name, password, invitationDigest);
        String digest = passwords.encode(password);
        Registration committed =
                transactions.execute(
                        () -> register(operation, invitationDigest, name, digest, fingerprint));
        CommandResult result = committed.result();
        if (committed.created())
            audit.record(
                    new AuditEvent(
                            operation, "invitation-holder", result.targetId(), result.action(), "completed"));
        return result;
    }

    /**
     * 锁定收据后再锁邀请；数据库失败必须同时回滚账号、邀请和收据。
     */
    private Registration register(
            UUID operation, String invitationDigest, String name, String digest, String fingerprint) {
        String scope = "invitation:" + invitationDigest;
        CommandResult previous = receipts.previous(scope, operation, fingerprint);
        RegistrationInvitation invitation = invitations.lockByDigest(invitationDigest);
        if (invitation == null || !invitation.loginName().equals(name)) {
            throw new IamException(IamFailure.CONFLICT, "邀请无效或已失效");
        }
        if (previous != null) {
            if (!previous.targetId().equals(invitation.consumedBy()))
                throw new IamException(IamFailure.CONFLICT, "注册结果不匹配");
            return new Registration(previous, false);
        }
        Account account = Account.register(UUID.randomUUID(), name, digest);
        RegistrationInvitation consumed;
        try {
            consumed = invitation.consume(account.id(), clock.now());
        } catch (IllegalStateException e) {
            throw new IamException(IamFailure.CONFLICT, "邀请无效或已失效");
        }
        accounts.insert(account);
        invitations.finish(consumed);
        CommandResult result =
                new CommandResult(operation, account.id(), account.version(), "invitation.accept");
        receipts.save(scope, fingerprint, result);
        return new Registration(result, true);
    }

    /**
     * 注册重放只交付原收据，不重造一次账号创建审计。
     */
    private record Registration(CommandResult result, boolean created) {
    }
}

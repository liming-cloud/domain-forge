package org.domainforge.iam.application.command.registration;

import org.domainforge.iam.application.authorization.CurrentAuthorization;
import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.IamFailure;
import org.domainforge.iam.application.port.audit.AuditEvent;
import org.domainforge.iam.application.port.audit.AuditTrail;
import org.domainforge.iam.application.port.authentication.SecretOperations;
import org.domainforge.iam.application.port.operation.DatabaseTime;
import org.domainforge.iam.application.port.operation.Identifiers;
import org.domainforge.iam.domain.model.account.Account;
import org.domainforge.iam.domain.model.invitation.RegistrationInvitation;
import org.domainforge.iam.domain.repository.AccountRepository;
import org.domainforge.iam.domain.repository.InvitationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * 创建或显式重发注册邀请；已消费的邀请不能借重发重置账号。
 */
@Service
public class IssueInvitation {
    private static final Duration VALIDITY = Duration.ofHours(24);
    private final DatabaseTime clock;
    private final CurrentAuthorization authorization;
    private final AccountRepository accounts;
    private final InvitationRepository invitations;
    private final Identifiers identifiers;
    private final SecretOperations secrets;
    private final AuditTrail audit;

    /**
     * 事务内重新读取操作者当前资格，邀请写入与审计一起提交。
     */
    public IssueInvitation(
            DatabaseTime clock,
            CurrentAuthorization authorization,
            AccountRepository accounts,
            InvitationRepository invitations,
            Identifiers identifiers,
            SecretOperations secrets,
            AuditTrail audit) {
        this.clock = clock;
        this.authorization = authorization;
        this.accounts = accounts;
        this.invitations = invitations;
        this.identifiers = identifiers;
        this.secrets = secrets;
        this.audit = audit;
    }

    /**
     * replacement 为空表示新邀请，否则使同一登录名的原邀请失效；有效期固定 24 小时。
     */
    @Transactional
    public InvitationIssued execute(String tokenDigest, String loginName, Long replacement) {
        String name = Account.normalizeLoginName(loginName);
        var actor = authorization.require(tokenDigest, "iam:invitation:create");
        if (accounts.findByLoginName(name) != null) throw new IamException(IamFailure.CONFLICT, "该登录名已不可邀请");
        if (replacement != null) invalidate(replacement, name);
        String secret = secrets.newSecret();
        var invitation = new RegistrationInvitation(
                identifiers.next(),
                name,
                secrets.digest(secret),
                clock.now().plus(VALIDITY),
                actor.accountId(),
                null,
                false);
        invitations.insert(invitation);
        audit.record(new AuditEvent(Long.toString(actor.accountId()), invitation.id(), "invitation.issue"));
        return new InvitationIssued(invitation.id(), secret);
    }

    /**
     * 重发不能换绑登录名；消费与重发竞争同一行锁，只有一个转换可提交。
     */
    private void invalidate(long id, String name) {
        RegistrationInvitation original = invitations.lock(id);
        if (original == null || !original.loginName().equals(name))
            throw new IamException(IamFailure.CONFLICT, "原邀请不可重发");
        try {
            invitations.finish(original.invalidate());
        } catch (IllegalStateException e) {
            throw new IamException(IamFailure.CONFLICT, "原邀请不可重发");
        }
    }
}

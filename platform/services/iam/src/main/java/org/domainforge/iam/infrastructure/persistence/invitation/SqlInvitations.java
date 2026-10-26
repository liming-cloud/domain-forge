package org.domainforge.iam.infrastructure.persistence.invitation;

import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.IamFailure;
import org.domainforge.iam.domain.model.RegistrationInvitation;
import org.domainforge.iam.domain.repository.InvitationRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * 邀请仓储验证更新结果，不能吞掉并发终态冲突。
 */
@Repository
public class SqlInvitations implements InvitationRepository {
    private final InvitationMapper mapper;

    /**
     * 使用服务唯一数据库的邀请Mapper。
     */
    public SqlInvitations(InvitationMapper mapper) {
        this.mapper = mapper;
    }

    private static RegistrationInvitation domain(InvitationRow row) {
        return row == null ? null : row.toDomain();
    }

    @Override
    public RegistrationInvitation lockByDigest(String digest) {
        return domain(mapper.byDigest(digest));
    }

    @Override
    public RegistrationInvitation lock(UUID id) {
        return domain(mapper.lock(id.toString()));
    }

    @Override
    public void insert(RegistrationInvitation invitation) {
        mapper.insert(InvitationRow.from(invitation));
    }

    /**
     * 已结束的邀请不能被第二次写入覆盖。
     */
    @Override
    public void finish(RegistrationInvitation invitation) {
        if (mapper.finish(InvitationRow.from(invitation)) != 1) {
            throw new IamException(IamFailure.CONFLICT, "邀请状态已变化");
        }
    }
}

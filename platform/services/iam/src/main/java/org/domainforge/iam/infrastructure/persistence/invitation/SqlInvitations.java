package org.domainforge.iam.infrastructure.persistence.invitation;

import org.domainforge.iam.domain.model.invitation.RegistrationInvitation;
import org.domainforge.iam.domain.repository.InvitationRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

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

    private static @Nullable RegistrationInvitation domain(@Nullable InvitationRow row) {
        return row == null ? null : row.toDomain();
    }

    @Override
    public @Nullable RegistrationInvitation lockByDigest(String digest) {
        return domain(mapper.byDigest(digest));
    }

    @Override
    public @Nullable RegistrationInvitation lock(long id) {
        return domain(mapper.lock(id));
    }

    @Override
    public void insert(RegistrationInvitation invitation) {
        mapper.insert(InvitationRow.from(invitation));
    }

    /**
     * 已结束的邀请不能被第二次写入覆盖。
     */
    @Override
    public boolean invalidate(RegistrationInvitation invitation) {
        return mapper.invalidate(InvitationRow.from(invitation)) == 1;
    }
}

package org.domainforge.iam.infrastructure.query.account;

import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.IamFailure;
import org.domainforge.iam.application.query.account.AccountPage;
import org.domainforge.iam.application.query.account.AccountQueries;
import org.domainforge.iam.application.query.account.AccountView;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * AccountQueries直接落实受保护的投影，不为简单查询强制装载领域聚合。
 */
@Repository
public class SqlAccountQueries implements AccountQueries {
    private final AccountQueryMapper mapper;

    /**
     * 只绑定查询Mapper，不接收仓储或写入能力。
     */
    public SqlAccountQueries(AccountQueryMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 不存在的可信主体不伪装为匿名账号。
     */
    @Override
    public AccountView self(UUID id) {
        AccountProjection row = mapper.self(id.toString());
        if (row == null) throw new IamException(IamFailure.UNAUTHENTICATED, "当前主体无效");
        return row.view();
    }

    /**
     * 业务投影每次恰好一条SQL；不静默修剪超大或非法分页输入。
     */
    @Override
    public AccountPage list(UUID after, int limit) {
        if (limit < 1 || limit > 100) throw new IamException(IamFailure.INVALID_REQUEST, "分页大小须为1至100");
        var items =
                mapper.list(after == null ? null : after.toString(), limit).stream()
                        .map(AccountProjection::view)
                        .toList();
        return new AccountPage(items, items.size() == limit ? items.getLast().id() : null);
    }
}

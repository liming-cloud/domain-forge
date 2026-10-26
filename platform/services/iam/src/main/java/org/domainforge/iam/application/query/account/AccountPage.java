package org.domainforge.iam.application.query.account;

import java.util.List;
import java.util.UUID;

/**
 * UUID升序键分页；nextCursor为空表示本次已不足一页，不执行额外COUNT查询。
 */
public record AccountPage(List<AccountView> items, UUID nextCursor) {
    /**
     * 返回不可变的有界投影集合。
     */
    public AccountPage {
        items = List.copyOf(items);
    }
}

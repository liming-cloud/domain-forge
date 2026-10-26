package org.domainforge.iam.application.query.account;

import java.util.List;

/**
 * 按账号 ID 升序的键分页；nextCursor 为空表示已不足一页，不执行额外 COUNT 查询。
 */
public record AccountPage(List<AccountView> items, Long nextCursor) {
    /**
     * 返回不可变的有界投影集合。
     */
    public AccountPage {
        items = List.copyOf(items);
    }
}

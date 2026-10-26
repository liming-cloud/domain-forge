package org.domainforge.iam.interfaces.http.account;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.domainforge.iam.application.query.account.AccountPage;

import java.util.List;

/**
 * 账号分页的 HTTP 表示；nextCursor 为空表示没有下一页，非空时作为下一次请求的 after。
 */
public record AccountPageResponse(
        List<AccountResponse> items, @JsonFormat(shape = JsonFormat.Shape.STRING) Long nextCursor) {
    /**
     * 从查询分页转换。
     */
    static AccountPageResponse of(AccountPage page) {
        return new AccountPageResponse(page.items().stream().map(AccountResponse::of).toList(), page.nextCursor());
    }
}

package org.domainforge.iam.interfaces.http.account;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.domainforge.iam.application.query.account.AccountView;

/**
 * 账号的 HTTP 表示；ID 以十进制字符串传输。
 */
public record AccountResponse(
        @JsonFormat(shape = JsonFormat.Shape.STRING) long id, String loginName, boolean enabled, long version) {
    /**
     * 从查询视图转换。
     */
    static AccountResponse of(AccountView view) {
        return new AccountResponse(view.id(), view.loginName(), view.enabled(), view.version());
    }
}

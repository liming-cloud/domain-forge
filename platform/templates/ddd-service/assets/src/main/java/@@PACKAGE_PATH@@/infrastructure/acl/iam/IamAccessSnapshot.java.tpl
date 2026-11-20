package @@PACKAGE@@.infrastructure.acl.iam;

import org.domainforge.shared.CurrentScope;
import org.domainforge.shared.CurrentSubject;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * IAM {@code GET /internal/access/snapshot} 的响应：当前主体与所请求权限的数据范围；缺项视为合同不符。
 *
 * @param subject 账号 ID、角色与精确权限
 * @param scopes  按权限码的数据范围
 */
public record IamAccessSnapshot(@Nullable CurrentSubject subject, @Nullable Map<String, CurrentScope> scopes) {
}

package org.domainforge.iam.interfaces.http.access;

import org.domainforge.shared.CurrentScope;
import org.domainforge.shared.CurrentSubject;

import java.util.Map;

/**
 * 当前主体与所请求权限的数据范围（{@code GET /internal/access/snapshot} 的响应）。
 *
 * @param subject 账号 ID、角色与精确权限，同 {@code /internal/access/current}
 * @param scopes  按权限码的数据范围，同 {@code /internal/access/scopes}
 */
public record AccessSnapshot(CurrentSubject subject, Map<String, CurrentScope> scopes) {
}

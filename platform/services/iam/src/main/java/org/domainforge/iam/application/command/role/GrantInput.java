package org.domainforge.iam.application.command.role;

import java.util.Set;

/**
 * 一项授予的输入；范围名称与组合规则由应用转换并交领域校验。
 *
 * @param permission 权限码
 * @param scope      数据范围名称
 * @param unitIds    自定义范围的组织 ID
 */
public record GrantInput(String permission, String scope, Set<Long> unitIds) {
}

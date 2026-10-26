package org.domainforge.iam.infrastructure.persistence.role;

/**
 * 授予表投影；unitId 仅自定义范围有值，同一授予的每个组织一行。
 */
public record GrantRow(String permissionCode, String scope, Long unitId) {
}

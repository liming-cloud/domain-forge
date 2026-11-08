package org.domainforge.iam.application.command.catalog;

/**
 * 权限目录项的输入。
 *
 * @param code       权限码
 * @param name       名称
 * @param scoped     授予时是否需要数据范围
 * @param systemOnly 是否系统专属
 */
public record PermissionInput(String code, String name, boolean scoped, boolean systemOnly) {
}

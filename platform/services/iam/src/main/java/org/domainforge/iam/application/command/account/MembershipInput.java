package org.domainforge.iam.application.command.account;

/**
 * 成员归属输入；转为领域值对象由应用完成。
 *
 * @param unitId  组织 ID
 * @param primary 是否主部门
 */
public record MembershipInput(long unitId, boolean primary) {
}

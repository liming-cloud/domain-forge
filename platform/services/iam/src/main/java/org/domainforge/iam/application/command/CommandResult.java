package org.domainforge.iam.application.command;

/**
 * 命令完成后的目标对象与其最新版本；不含密码、令牌或邀请秘密。
 *
 * @param targetId 被创建或修改的对象 ID
 * @param version  对象在本次提交后的版本，客户端用于下一次并发校验
 */
public record CommandResult(long targetId, long version) {
}

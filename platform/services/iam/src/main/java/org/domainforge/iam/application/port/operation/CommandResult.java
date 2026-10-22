package org.domainforge.iam.application.port.operation;

import java.util.UUID;

/** 可重读的命令结果不含密码、令牌或邀请秘密；版本指完成当时的版本。 */
public record CommandResult(UUID operationId, UUID targetId, long version, String action) {}

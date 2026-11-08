package org.domainforge.iam.application.command;

import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.IamFailure;

/**
 * 乐观并发的目标与版本检查：参数无效为 400，对象不存在或版本已变化为 409。
 */
public final class ExpectedVersion {
    private ExpectedVersion() {
    }

    /**
     * 版本参数必须为正数。
     */
    public static void require(long id, long version) {
        if (id <= 0 || version < 1) throw new IamException(IamFailure.INVALID_REQUEST, "目标与版本无效");
    }

    /**
     * 目标不存在或版本已变化均为冲突，客户端应重新读取后再操作。
     */
    public static <T> T current(T current, long actualVersion, long expectedVersion) {
        if (current == null || actualVersion != expectedVersion)
            throw new IamException(IamFailure.CONFLICT, "对象不存在或版本已变化");
        return current;
    }
}

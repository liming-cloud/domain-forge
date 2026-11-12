package org.domainforge.iam.application.command;

import org.domainforge.iam.application.port.IamException;
import org.jspecify.annotations.Nullable;

import java.util.function.ToLongFunction;

/**
 * 乐观并发的目标与版本检查：参数无效为 400，目标不存在为 404，版本已变化为 409。
 */
public final class ExpectedVersion {
    private ExpectedVersion() {
    }

    /**
     * 版本参数必须为正数。
     */
    public static void require(long id, long version) {
        if (id <= 0 || version < 1) throw IamException.invalid("目标与版本无效");
    }

    /**
     * 目标不存在时按调用方给的错误码返回 404；版本已变化为冲突，客户端应重新读取后再操作。
     *
     * @param current         读取到的目标，不存在时为空
     * @param version         取目标当前版本
     * @param expectedVersion 客户端提交的版本
     * @param notFoundCode    目标不存在时的稳定错误码
     * @return 版本一致的目标
     */
    public static <T> T current(
            @Nullable T current, ToLongFunction<T> version, long expectedVersion, String notFoundCode) {
        if (current == null) throw IamException.notFound(notFoundCode, "目标不存在");
        if (version.applyAsLong(current) != expectedVersion)
            throw IamException.conflict("VERSION_CONFLICT", "版本已变化，请重新读取后再操作");
        return current;
    }
}

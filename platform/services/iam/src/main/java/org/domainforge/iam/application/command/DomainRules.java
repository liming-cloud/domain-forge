package org.domainforge.iam.application.command;

import org.domainforge.iam.application.port.IamException;
import org.domainforge.iam.application.port.IamFailure;

import java.util.function.Supplier;

/**
 * 把领域规则拒绝翻译为稳定失败分类：输入不合规为 400，当前状态不允许为 409。
 */
public final class DomainRules {
    private DomainRules() {
    }

    /**
     * 执行领域转换并翻译其拒绝；领域消息是固定说明，可以对外返回。
     */
    public static <T> T apply(Supplier<T> transition) {
        try {
            return transition.get();
        } catch (IllegalArgumentException rejected) {
            throw new IamException(IamFailure.INVALID_REQUEST, rejected.getMessage());
        } catch (IllegalStateException rejected) {
            throw new IamException(IamFailure.CONFLICT, rejected.getMessage());
        }
    }

    /**
     * 只做校验、没有结果的领域规则。
     */
    public static void check(Runnable rule) {
        apply(() -> {
            rule.run();
            return null;
        });
    }

    /**
     * 版本参数必须为正数。
     */
    public static void requireVersion(long id, long version) {
        if (id <= 0 || version < 1) throw new IamException(IamFailure.INVALID_REQUEST, "目标与版本无效");
    }

    /**
     * 目标不存在或版本已变化均为冲突，客户端应重新读取后再操作。
     */
    public static <T> T requireCurrent(T current, long actualVersion, long expectedVersion) {
        if (current == null || actualVersion != expectedVersion)
            throw new IamException(IamFailure.CONFLICT, "对象不存在或版本已变化");
        return current;
    }
}

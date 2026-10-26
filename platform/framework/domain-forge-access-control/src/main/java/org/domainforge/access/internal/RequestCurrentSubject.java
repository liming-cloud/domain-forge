package org.domainforge.access.internal;

import cn.dev33.satoken.context.SaHolder;
import org.domainforge.access.api.AccessException;
import org.domainforge.access.api.AccessFailure;
import org.domainforge.access.api.CurrentSubjectSource;
import org.domainforge.shared.CurrentSubject;

/**
 * 仅按本请求实际令牌复用完整主体，请求结束即释放，不建立跨请求允许缓存。
 */
public final class RequestCurrentSubject {
    private static final String KEY = RequestCurrentSubject.class.getName();
    private final CurrentSubjectSource source;

    /**
     * 事实源由应用提供：IAM 读本地数据库，其他服务调用 IAM。
     */
    public RequestCurrentSubject(CurrentSubjectSource source) {
        this.source = source;
    }

    /**
     * 令牌变化重新读取；无身份转换为原生异常，让原生可选查询返回空或 false。
     */
    public CurrentSubject current(String token) {
        var storage = SaHolder.getStorage();
        if (token == null || token.isBlank()) {
            storage.delete(KEY);
            throw NativeFailures.missing();
        }
        if (storage.get(KEY) instanceof Snapshot cached && cached.token().equals(token)) return cached.subject();
        storage.delete(KEY);
        try {
            CurrentSubject subject = source.resolve(token);
            if (subject == null) throw new AccessException(AccessFailure.UNAVAILABLE);
            storage.set(KEY, new Snapshot(token, subject));
            return subject;
        } catch (AccessException failure) {
            if (failure.failure() == AccessFailure.UNAUTHENTICATED) throw NativeFailures.missing();
            throw failure;
        }
    }

    /**
     * 请求内凭证与完整主体的不可变绑定，不暴露给业务或写入日志。
     */
    private record Snapshot(String token, CurrentSubject subject) {
        @Override
        public String toString() {
            return "Snapshot[token=***]";
        }
    }
}

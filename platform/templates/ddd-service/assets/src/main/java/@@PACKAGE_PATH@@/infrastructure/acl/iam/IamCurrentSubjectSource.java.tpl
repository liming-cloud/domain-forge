package @@PACKAGE@@.infrastructure.acl.iam;

import org.domainforge.access.api.CurrentSubjectSource;
import org.domainforge.shared.CurrentSubject;
import org.springframework.stereotype.Component;

/**
 * 本服务的当前主体事实源：每个请求经访问组件最多调用一次 IAM，跨请求不缓存允许结果。
 *
 * <p>本服务权限目录中有需要数据范围的权限时，同一次调用一并取回这些范围（见 {@link ScopePrefetch}），用例读范围时不再调用 IAM。
 */
@Component
public class IamCurrentSubjectSource implements CurrentSubjectSource {
    private final IamAccessClient client;
    private final ScopePrefetch prefetch;

    /**
     * 只依赖本服务声明的 IAM 客户端与本请求的范围暂存。
     */
    public IamCurrentSubjectSource(IamAccessClient client, ScopePrefetch prefetch) {
        this.client = client;
        this.prefetch = prefetch;
    }

    @Override
    public CurrentSubject resolve(String token) {
        try {
            if (prefetch.permissions().isEmpty()) return IamFailures.data(client.current("Bearer " + token));
            IamAccessSnapshot snapshot = IamFailures.data(client.snapshot("Bearer " + token, prefetch.permissions()));
            CurrentSubject subject = IamFailures.data(snapshot.subject());
            prefetch.remember(token, IamFailures.data(snapshot.scopes()));
            return subject;
        } catch (RuntimeException failure) {
            throw IamFailures.translate(failure);
        }
    }
}

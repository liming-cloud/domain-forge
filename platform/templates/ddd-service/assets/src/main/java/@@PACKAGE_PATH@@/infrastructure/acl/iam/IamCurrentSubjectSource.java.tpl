package @@PACKAGE@@.infrastructure.acl.iam;

import org.domainforge.access.api.CurrentSubjectSource;
import org.domainforge.shared.CurrentSubject;
import org.springframework.stereotype.Component;

/**
 * 本服务的当前主体事实源：每个请求经访问组件最多调用一次 IAM，跨请求不缓存允许结果。
 */
@Component
public class IamCurrentSubjectSource implements CurrentSubjectSource {
    private final IamAccessClient client;

    /**
     * 只依赖本服务声明的 IAM 客户端。
     */
    public IamCurrentSubjectSource(IamAccessClient client) {
        this.client = client;
    }

    @Override
    public CurrentSubject resolve(String token) {
        try {
            return IamFailures.data(client.current("Bearer " + token));
        } catch (RuntimeException failure) {
            throw IamFailures.translate(failure);
        }
    }
}

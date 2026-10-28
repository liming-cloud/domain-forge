package @@PACKAGE@@.infrastructure.acl.iam;

import cn.dev33.satoken.stp.StpLogic;
import @@PACKAGE@@.application.port.access.DataScopes;
import org.domainforge.access.api.AccessException;
import org.domainforge.access.api.AccessFailure;
import org.domainforge.shared.CurrentScope;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 用当前请求原生解析的令牌向 IAM 读取数据范围；结果缺项视为合同不符而不是空范围。
 */
@Component
public class IamDataScopes implements DataScopes {
    private final IamAccessClient client;
    private final StpLogic logic;

    /**
     * 令牌只来自原生会话逻辑，不接收调用方参数。
     */
    public IamDataScopes(IamAccessClient client, StpLogic logic) {
        this.client = client;
        this.logic = logic;
    }

    @Override
    public CurrentScope scope(String permission) {
        String token = logic.getTokenValue();
        if (token == null || token.isBlank()) throw new AccessException(AccessFailure.UNAUTHENTICATED);
        try {
            var scopes = IamFailures.data(client.scopes("Bearer " + token, List.of(permission)));
            CurrentScope scope = scopes.get(permission);
            if (scope == null) throw new AccessException(AccessFailure.UNAVAILABLE);
            return scope;
        } catch (RuntimeException failure) {
            throw IamFailures.translate(failure);
        }
    }
}

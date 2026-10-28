package org.domainforge.gateway.infrastructure.filter;

import cn.dev33.satoken.context.SaHolder;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.reactor.filter.SaReactorFilter;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import org.domainforge.config.api.DynamicSettings;
import org.domainforge.gateway.infrastructure.routing.GatewaySettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.PathContainer;

/**
 * 按 Sa-Token 官方网关鉴权用法装配的登录前置校验：{@link SaReactorFilter} 中以 {@link SaRouter} 匹配，
 * 不在任一路由匿名清单中的请求执行 {@code StpUtil.checkLogin()}。
 *
 * <p>只是提前拒绝：后端仍完整认证（SQL 许可、凭据版本）与授权。未登录 401，会话存储不可用 503。
 * 原生过滤器在事件循环线程上同步读取 Redis（Sa-Token 响应式集成的既定方式），读取超时由 Redis 客户端配置限定。
 */
public final class LoginCheck {
    private static final Logger LOG = LoggerFactory.getLogger(LoginCheck.class);
    /**
     * WebFlux 的管理端口复用主应用的 WebFilter，健康检查不能要求登录；主端口上这些路径不在任何路由中，仍为 404。
     */
    private static final String MANAGEMENT = "/actuator/**";
    private static final String UNAUTHENTICATED =
            "{\"code\":\"UNAUTHENTICATED\",\"message\":\"需要身份认证\",\"data\":null}";
    private static final String UNAVAILABLE =
            "{\"code\":\"SERVICE_UNAVAILABLE\",\"message\":\"服务暂不可用\",\"data\":null}";

    private LoginCheck() {
    }

    /**
     * 构造全局过滤器；匿名清单每次请求读取当前配置快照。
     *
     * @param settings 路由配置组
     * @return 已配置的过滤器
     */
    public static SaReactorFilter filter(DynamicSettings<GatewaySettings> settings) {
        return new SaReactorFilter()
                .addInclude("/**")
                .addExclude(MANAGEMENT)
                .setAuth(ignored -> SaRouter.match(!anonymous(settings)).check(route -> StpUtil.checkLogin()))
                .setError(LoginCheck::respond);
    }

    private static boolean anonymous(DynamicSettings<GatewaySettings> settings) {
        var request = SaHolder.getRequest();
        PathContainer path = PathContainer.parsePath(request.getRequestPath());
        String method = request.getMethod();
        return settings.current().routes().stream().anyMatch(route -> route.allowsAnonymous(method, path));
    }

    private static Object respond(Throwable failure) {
        var response = SaHolder.getResponse();
        response.setHeader("Content-Type", "application/json");
        if (failure instanceof NotLoginException) {
            response.setStatus(401);
            return UNAUTHENTICATED;
        }
        LOG.error("gateway_session_unavailable reason={}", failure.getClass().getName());
        response.setStatus(503);
        return UNAVAILABLE;
    }
}

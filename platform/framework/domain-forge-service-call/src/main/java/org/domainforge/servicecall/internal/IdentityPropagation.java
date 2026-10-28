package org.domainforge.servicecall.internal;

import cn.dev33.satoken.same.SaSameUtil;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.domainforge.servicecall.api.ServiceCallException;
import org.domainforge.servicecall.api.ServiceCallFailure;
import org.slf4j.MDC;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 每次 Feign 调用前写入身份与关联信息（Sa-Token 文档中服务间调用的 FeignInterceptor 做法）：
 * <ul>
 *   <li>Same-Token：证明调用来自系统内服务，被调方据此放行；读取失败时不发出请求。</li>
 *   <li>Authorization：当前 HTTP 请求带有用户令牌时原样转发，被调方以同一用户身份认证授权；
 *       客户端方法已显式声明该头时不覆盖。没有用户上下文（定时任务等）时不带，只能调用被调方的服务专用接口。</li>
 *   <li>X-Request-Id：沿用当前请求的标识，被调方日志可与之关联。</li>
 * </ul>
 */
public final class IdentityPropagation implements RequestInterceptor {
    static final String SAME_TOKEN = SaSameUtil.SAME_TOKEN;
    static final String AUTHORIZATION = "Authorization";
    static final String REQUEST_ID = "X-Request-Id";

    @Override
    public void apply(RequestTemplate template) {
        template.removeHeader(SAME_TOKEN);
        template.header(SAME_TOKEN, sameToken());
        if (!template.headers().containsKey(AUTHORIZATION)) {
            String authorization = currentAuthorization();
            if (authorization != null) template.header(AUTHORIZATION, authorization);
        }
        String requestId = MDC.get("requestId");
        if (requestId != null && !template.headers().containsKey(REQUEST_ID)) template.header(REQUEST_ID, requestId);
    }

    private static String sameToken() {
        try {
            return SaSameUtil.getToken();
        } catch (RuntimeException failure) {
            throw new ServiceCallException(ServiceCallFailure.UNAVAILABLE, null, failure);
        }
    }

    private static String currentAuthorization() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) return null;
        String value = attributes.getRequest().getHeader(AUTHORIZATION);
        return value == null || value.isBlank() ? null : value;
    }
}

package org.domainforge.iam.interfaces.http.protocol;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * IAM JSON输入有界且响应不缓存；对分块请求同样限制实际读取字节，不信任Content-Length。
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class IamRequestFilter extends OncePerRequestFilter {
    /**
     * 读取上限包含超限检测字节；凭据仅保留于本次请求内存。
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        byte[] bytes = request.getInputStream().readNBytes(16385);
        if (bytes.length > 16384) {
            response.setStatus(400);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response
                    .getWriter()
                    .write("{\"code\":\"INVALID_REQUEST\",\"message\":\"请求内容过大\",\"data\":null}");
            return;
        }
        chain.doFilter(new BoundedJsonRequest(request, bytes), response);
    }
}

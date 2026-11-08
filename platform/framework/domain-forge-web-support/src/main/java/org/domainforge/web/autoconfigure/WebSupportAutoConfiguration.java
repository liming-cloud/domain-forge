package org.domainforge.web.autoconfigure;

import org.domainforge.web.internal.AdviceConflicts;
import org.domainforge.web.internal.MultipartExceptionAdvice;
import org.domainforge.web.internal.PlatformExceptionAdvice;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;

/**
 * Servlet MVC 专用默认装配；非 Web 容器不注册 Advice。
 */
@AutoConfiguration
@ConditionalOnClass(DispatcherServlet.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WebSupportAutoConfiguration {
    /**
     * 注册仅适用于标记入口的异常处理器。
     *
     * @return 平台内部 Advice
     */
    @Bean
    PlatformExceptionAdvice platformExceptionAdvice() {
        return new PlatformExceptionAdvice();
    }

    /**
     * multipart 解析失败发生在选定处理器之前，需要全局接管。
     *
     * @return 平台内部 Advice
     */
    @Bean
    MultipartExceptionAdvice multipartExceptionAdvice() {
        return new MultipartExceptionAdvice();
    }

    /**
     * 对真实宿主注册执行冲突检查。
     *
     * @param context  当前容器
     * @param handlers MVC 注册
     * @return 启动检查
     */
    @Bean
    AdviceConflicts adviceConflicts(
            ApplicationContext context, List<RequestMappingHandlerMapping> handlers) {
        return new AdviceConflicts(context, handlers);
    }
}

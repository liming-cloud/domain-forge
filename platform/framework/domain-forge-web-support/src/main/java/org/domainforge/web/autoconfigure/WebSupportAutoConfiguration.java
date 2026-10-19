package org.domainforge.web.autoconfigure;

import java.util.List;
import org.domainforge.web.api.BusinessRejectionMapping;
import org.domainforge.web.internal.AdviceConflicts;
import org.domainforge.web.internal.PlatformExceptionAdvice;
import org.domainforge.web.internal.RejectionMappings;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Servlet MVC 专用默认装配；非 Web 容器不注册映射或 Advice。 */
@AutoConfiguration
@ConditionalOnClass(DispatcherServlet.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WebSupportAutoConfiguration {
  /**
   * 固定所有公开扩展，无扩展时使用空表。
   *
   * @param mappings 消费方映射 Bean
   * @return 已校验的映射表
   */
  @Bean
  RejectionMappings rejectionMappings(List<BusinessRejectionMapping<?>> mappings) {
    return new RejectionMappings(mappings);
  }

  /**
   * 注册仅适用于标记入口的异常处理器。
   *
   * @param mappings 已校验映射
   * @return 平台内部 Advice
   */
  @Bean
  PlatformExceptionAdvice platformExceptionAdvice(RejectionMappings mappings) {
    return new PlatformExceptionAdvice(mappings);
  }

  /**
   * 对真实宿主注册执行冲突检查。
   *
   * @param context 当前容器
   * @param handlers MVC 注册
   * @return 启动检查
   */
  @Bean
  AdviceConflicts adviceConflicts(
      ApplicationContext context, List<RequestMappingHandlerMapping> handlers) {
    return new AdviceConflicts(context, handlers);
  }
}

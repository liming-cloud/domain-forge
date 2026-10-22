package org.domainforge.access.backend.configuration;

import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpLogic;
import org.domainforge.access.api.CurrentAccess;
import org.domainforge.access.api.CurrentSubjectSource;
import org.domainforge.access.backend.internal.execution.AccessDeclarationInterceptor;
import org.domainforge.access.backend.internal.execution.AccessDeclarations;
import org.domainforge.access.backend.internal.execution.BackendFailureAdvice;
import org.domainforge.access.backend.internal.execution.CurrentSubjectPermissions;
import org.domainforge.access.backend.internal.execution.NativeAccessInterceptor;
import org.domainforge.access.backend.internal.execution.NativeAccessOwner;
import org.domainforge.access.backend.internal.execution.RequestCurrentSubject;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 本地与远程模式共享的访问执行链；不加载事实源自身的业务或传输配置。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Import(BackendFailureAdvice.class)
public class AccessControlConfiguration {
  /** 当前请求的完整主体按令牌绑定；在非Web维护进程不创建。 */
  @Bean
  public RequestCurrentSubject requestCurrentSubject(CurrentSubjectSource source) {
    return new RequestCurrentSubject(source);
  }

  /** 当前主体只从原生实际Header令牌建立，缺失身份沿用原生失败。 */
  @Bean
  public CurrentAccess currentAccess(RequestCurrentSubject subjects, StpLogic logic) {
    return () -> subjects.current(logic.getTokenValue());
  }

  /** 原生扩展点统一供给角色和权限，无getPermissionList子类覆盖。 */
  @Bean
  public StpInterface accessStpInterface(CurrentAccess current, StpLogic logic) {
    return new CurrentSubjectPermissions(current, logic);
  }

  /** 声明范围与原生运行规则分离，避免重新实现组合判定。 */
  @Bean
  public AccessDeclarations accessDeclarations() {
    return new AccessDeclarations();
  }

  /** 明确拥有并验证进程级装配；宿主重复定义导致启动失败。 */
  @Bean
  public NativeAccessOwner nativeAccessOwner(
      ApplicationContext context,
      StpLogic logic,
      StpInterface provider,
      AccessDeclarations declarations) {
    return new NativeAccessOwner(context, logic, provider, declarations);
  }

  /** 先拒绝遗漏与验证完整事实，再交原生执行登录／权限／角色及AND／OR。 */
  @Bean
  public WebMvcConfigurer accessWebMvcConfigurer(
      AccessDeclarations declarations, CurrentAccess current, StpLogic logic) {
    return new WebMvcConfigurer() {
      @Override
      public void addInterceptors(InterceptorRegistry registry) {
        registry
            .addInterceptor(new AccessDeclarationInterceptor(declarations, current, logic))
            .order(-100)
            .addPathPatterns("/**");
        registry.addInterceptor(new NativeAccessInterceptor()).order(-99).addPathPatterns("/**");
      }
    };
  }
}

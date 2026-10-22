package org.domainforge.access.backend.internal.execution;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.fun.strategy.SaGetAnnotationFunction;
import cn.dev33.satoken.fun.strategy.SaIsAnnotationPresentFunction;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.strategy.SaAnnotationStrategy;
import cn.dev33.satoken.strategy.SaStrategy;
import org.domainforge.access.api.CurrentSubjectSource;
import org.domainforge.access.backend.configuration.BackendAccessConfiguration;
import org.domainforge.access.backend.configuration.LocalAccessConfiguration;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** 唯一应用上下文拥有进程级原生装配和合成注解策略；冲突拒绝且关闭时释放所有权。 */
public final class NativeAccessOwner implements SmartInitializingSingleton, DisposableBean {
  private static NativeAccessOwner owner;
  private final ApplicationContext context;
  private final StpLogic logic;
  private final StpInterface provider;
  private final AccessDeclarations declarations;
  private SaGetAnnotationFunction previousGet;
  private SaIsAnnotationPresentFunction previousPresent;
  private SaGetAnnotationFunction mergedGet;
  private SaIsAnnotationPresentFunction mergedPresent;

  /** 校验原生Spring生命周期的实际装配结果，不把框架静态默认值误判为自定义Bean。 */
  public NativeAccessOwner(
      ApplicationContext context,
      StpLogic logic,
      StpInterface provider,
      AccessDeclarations declarations) {
    this.context = context;
    this.logic = logic;
    this.provider = provider;
    this.declarations = declarations;
  }

  @Override
  public void afterSingletonsInstantiated() {
    synchronized (NativeAccessOwner.class) {
      validateComposition();
      var strategy = SaAnnotationStrategy.instance;
      validateStrategy(strategy);
      previousGet = strategy.getAnnotation;
      previousPresent = strategy.isAnnotationPresent;
      mergedGet = (element, kind) -> AnnotatedElementUtils.findMergedAnnotation(element, kind);
      mergedPresent =
          (element, kind) ->
              AnnotatedElementUtils.hasAnnotation(element, kind)
                  || AnnotatedElementUtils.hasAnnotation(element.getDeclaringClass(), kind);
      strategy.getAnnotation = mergedGet;
      strategy.isAnnotationPresent = mergedPresent;
      owner = this;
    }
    for (var mapping : context.getBeansOfType(RequestMappingHandlerMapping.class).values())
      for (var method : mapping.getHandlerMethods().values())
        if (!AccessDeclarationInterceptor.framework(method)) declarations.policy(method);
  }

  /** 只接受本上下文唯一事实源、逻辑、提供者及明确模式，核对原生实际注册结果。 */
  private void validateComposition() {
    if (owner != null
        || context.getBeansOfType(StpLogic.class).size() != 1
        || context.getBeansOfType(StpInterface.class).size() != 1
        || context.getBeansOfType(CurrentSubjectSource.class).size() != 1
        || context.getBeansOfType(LocalAccessConfiguration.class).size()
                + context.getBeansOfType(BackendAccessConfiguration.class).size()
            != 1
        || StpUtil.getStpLogic() != logic
        || SaManager.getStpLogic("login") != logic
        || SaManager.getStpInterface() != provider)
      throw new IllegalStateException("访问接入存在重复或冲突装配");
  }

  /** 受支持原生执行器必须完整且未被宿主替换，不限制产品未支持的分支。 */
  private void validateStrategy(SaAnnotationStrategy strategy) {
    if (!nativeStrategy(strategy.getAnnotation, SaAnnotationStrategy.class)
        || !nativeStrategy(strategy.isAnnotationPresent, SaAnnotationStrategy.class)
        || !nativeStrategy(strategy.checkMethodAnnotation, SaAnnotationStrategy.class)
        || !nativeStrategy(strategy.checkElementAnnotation, SaAnnotationStrategy.class)
        || !nativeStrategy(SaStrategy.instance.hasElement, SaStrategy.class))
      throw new IllegalStateException("原生访问策略已被其他装配修改");
    var supported =
        java.util.Set.of(
            cn.dev33.satoken.annotation.SaCheckLogin.class,
            cn.dev33.satoken.annotation.SaCheckPermission.class,
            cn.dev33.satoken.annotation.SaCheckRole.class,
            cn.dev33.satoken.annotation.SaCheckOr.class);
    if (!strategy.annotationHandlerMap.keySet().containsAll(supported))
      throw new IllegalStateException("原生注解执行器清单已被修改");
    for (var kind : supported)
      if (!strategy
          .annotationHandlerMap
          .get(kind)
          .getClass()
          .getName()
          .equals("cn.dev33.satoken.annotation.handler." + kind.getSimpleName() + "Handler"))
        throw new IllegalStateException("原生注解执行器已被其他装配修改");
  }

  /** 固定框架版本的默认函数由原生类持有，拒绝宿主替换的执行／匹配策略。 */
  private static boolean nativeStrategy(Object function, Class<?> nativeOwner) {
    return function != null && function.getClass().getNestHost() == nativeOwner;
  }

  @Override
  public void destroy() {
    synchronized (NativeAccessOwner.class) {
      if (owner != this) return;
      var strategy = SaAnnotationStrategy.instance;
      if (strategy.getAnnotation == mergedGet) strategy.getAnnotation = previousGet;
      if (strategy.isAnnotationPresent == mergedPresent)
        strategy.isAnnotationPresent = previousPresent;
      owner = null;
    }
  }
}

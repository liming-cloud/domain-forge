package probe.persistence.assembly;

import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import java.lang.reflect.Method;
import java.util.Map;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import probe.persistence.application.InventoryUseCase;

/** 复制到隔离应用源码的合成装配；观察器不安装或补救该路由。 */
@Configuration(proxyBeanMethods = false)
public class ResourceRoutingFixture {

  /** 将具体用例操作绑定到真实资源，在事务切面前设置并恢复路由上下文。 */
  @Bean
  public Advisor resourceRouting(ObjectProvider<DynamicRoutingDataSource> sources)
      throws NoSuchMethodException {
    Map<Method, String> bindings =
        Map.of(
            InventoryUseCase.class.getMethod("read"), "inventory",
            InventoryUseCase.class.getMethod("commit"), "inventory",
            InventoryUseCase.class.getMethod("rollback"), "inventory");
    StaticMethodMatcherPointcut pointcut =
        new StaticMethodMatcherPointcut() {
          /** 仅匹配显式绑定的目标方法，不按包名或类名猜测资源。 */
          @Override
          public boolean matches(Method method, Class<?> targetClass) {
            return bindings.containsKey(AopUtils.getMostSpecificMethod(method, targetClass));
          }
        };
    MethodInterceptor advice =
        invocation -> {
          Method method =
              AopUtils.getMostSpecificMethod(
                  invocation.getMethod(), invocation.getThis().getClass());
          String alias = bindings.get(method);
          DynamicRoutingDataSource source = sources.getObject();
          if (!source.getDataSources().containsKey(alias)) {
            throw new IllegalStateException("Unregistered resource");
          }
          if (TransactionSynchronizationManager.isActualTransactionActive()
              && !alias.equals(DynamicDataSourceContextHolder.peek())) {
            throw new IllegalStateException("Resource cannot change in transaction");
          }
          DynamicDataSourceContextHolder.push(alias);
          try {
            return invocation.proceed();
          } finally {
            DynamicDataSourceContextHolder.poll();
          }
        };
    DefaultPointcutAdvisor advisor = new DefaultPointcutAdvisor(pointcut, advice);
    advisor.setOrder(0);
    return advisor;
  }
}

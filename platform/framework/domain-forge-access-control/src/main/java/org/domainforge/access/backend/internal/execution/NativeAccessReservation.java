package org.domainforge.access.backend.internal.execution;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

/** 在普通单例和原生注入前取得进程所有权，第二上下文失败不能覆盖已运行应用。 */
public final class NativeAccessReservation implements BeanFactoryPostProcessor, DisposableBean {
  private static NativeAccessReservation active;
  private StpLogic previousLogic;
  private StpInterface previousProvider;

  @Override
  public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
    synchronized (NativeAccessReservation.class) {
      if (active != null) throw new IllegalStateException("一个进程只支持一个访问接入上下文");
      previousLogic = StpUtil.getStpLogic();
      previousProvider = SaManager.getStpInterface();
      if (previousLogic.getClass() != StpLogic.class
          || previousLogic.getConfig() != null
          || previousProvider.getClass() != cn.dev33.satoken.stp.StpInterfaceDefaultImpl.class)
        throw new IllegalStateException("宿主已有非默认身份装配");
      active = this;
    }
  }

  @Override
  public void destroy() {
    synchronized (NativeAccessReservation.class) {
      if (active != this) return;
      StpUtil.setStpLogic(previousLogic);
      SaManager.setStpInterface(previousProvider);
      active = null;
    }
  }
}

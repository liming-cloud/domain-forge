package org.domainforge.access.backend.configuration;

import cn.dev33.satoken.stp.StpLogic;
import org.domainforge.access.backend.internal.execution.NativeAccessReservation;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 业务应用的本地模式；正常原生会话资源不依赖完整事实源，避免身份协作循环。 */
@Configuration(proxyBeanMethods = false)
@Import(AccessControlConfiguration.class)
public class LocalAccessConfiguration {
  /** 在任何原生资源注入前取得唯一进程所有权，拒绝另一个上下文覆盖全局状态。 */
  @Bean
  public static NativeAccessReservation accessNativeReservation() {
    return new NativeAccessReservation();
  }

  /** 同一原生逻辑同时用于应用会话生命周期和公共入口校验。 */
  @Bean
  public StpLogic accessStpLogic() {
    return new StpLogic("login");
  }
}

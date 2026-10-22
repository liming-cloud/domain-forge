package org.domainforge.access.backend.configuration;

import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.stp.StpLogic;
import org.domainforge.access.backend.internal.execution.NativeAccessReservation;
import org.domainforge.access.backend.internal.execution.RequestCurrentSubject;
import org.domainforge.access.backend.internal.remote.BackendStpLogic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 业务应用的外部事实模式；来源由基础设施提供，不装配HTTP或发现客户端。 */
@Configuration(proxyBeanMethods = false)
@Import(AccessControlConfiguration.class)
public class BackendAccessConfiguration {
  /** 在任何原生资源注入前取得唯一进程所有权，拒绝另一个上下文覆盖全局状态。 */
  @Bean
  public static NativeAccessReservation accessNativeReservation() {
    return new NativeAccessReservation();
  }

  /** 远端只读取Header令牌，不建立cookie或表单凭证路径。 */
  @Bean
  public StpLogic accessStpLogic(RequestCurrentSubject subjects) {
    var config = new SaTokenConfig();
    config.setTokenName("Authorization");
    config.setTokenPrefix("Bearer");
    config.setIsReadCookie(false);
    config.setIsReadBody(false);
    config.setIsLog(false);
    var logic = new BackendStpLogic(subjects);
    logic.setConfig(config);
    return logic;
  }
}

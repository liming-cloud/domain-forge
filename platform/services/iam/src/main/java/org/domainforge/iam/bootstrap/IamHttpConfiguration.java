package org.domainforge.iam.bootstrap;

import java.util.Arrays;
import java.util.Set;
import org.domainforge.iam.interfaces.security.ServiceCertificateIdentity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

/** IAM只装配自身服务端TLS与调用方身份；通用访问执行由公共组件统一提供。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableScheduling
public class IamHttpConfiguration {
  /** 原生TLS终止及信任库是必要部署输入，禁止用转发头或明文替代。 */
  @Bean
  public ServiceCertificateIdentity serviceCertificates(Environment environment) {
    if (!environment.getRequiredProperty("server.ssl.enabled", Boolean.class)
        || !"want".equals(environment.getRequiredProperty("server.ssl.client-auth"))) {
      throw new IllegalArgumentException("IAM需要原生TLS及可选客户端证书握手");
    }
    environment.getRequiredProperty("server.ssl.key-store");
    environment.getRequiredProperty("server.ssl.trust-store");
    String project = environment.getRequiredProperty("iam.project-id");
    Set<String> services =
        Set.copyOf(
            Arrays.asList(
                environment.getRequiredProperty("iam.trusted-service-identities").split(",")));
    return new ServiceCertificateIdentity(project, services);
  }
}

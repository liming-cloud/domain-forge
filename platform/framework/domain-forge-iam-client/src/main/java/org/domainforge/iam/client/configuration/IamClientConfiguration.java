package org.domainforge.iam.client.configuration;

import org.domainforge.access.api.CurrentSubjectSource;
import org.domainforge.iam.client.internal.IamAccessFeignClient;
import org.domainforge.iam.client.internal.IamCurrentSubjectSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** 显式启用 IAM 具名服务客户端；服务发现和注册配置由消费应用通过原生属性提供。 */
@Configuration(proxyBeanMethods = false)
@EnableFeignClients(clients = IamAccessFeignClient.class)
public class IamClientConfiguration {
  /** 原生 Feign ensemble 在单例装配完成后初始化，既不触发远程请求，也不制造 MVC 循环依赖。 */
  @Bean
  public org.springframework.beans.factory.SmartInitializingSingleton iamClientReady(
      ObjectProvider<IamAccessFeignClient> client) {
    return client::getObject;
  }

  /** 启动校验原生 TLS 材料并建立本应用的有限事实来源。 */
  @Bean
  public CurrentSubjectSource iamCurrentSubjectSource(
      ObjectProvider<IamAccessFeignClient> client, SslBundles bundles, Environment env)
      throws java.security.KeyStoreException {
    var stores =
        bundles
            .getBundle(env.getProperty("domainforge.iam-client.ssl-bundle", "iam-client"))
            .getStores();
    if (stores.getKeyStore() == null
        || stores.getTrustStore() == null
        || stores.getTrustStore().size() == 0)
      throw new IllegalArgumentException("IAM 双向 TLS 需要本服务密钥和信任材料");
    boolean key = false;
    var aliases = stores.getKeyStore().aliases();
    while (aliases.hasMoreElements())
      if (stores.getKeyStore().isKeyEntry(aliases.nextElement())) key = true;
    if (!key) throw new IllegalArgumentException("IAM 双向 TLS 缺少本服务私钥");
    return new IamCurrentSubjectSource(
        client::getObject, env.getRequiredProperty("domainforge.iam-client.project-id"));
  }
}

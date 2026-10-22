package org.domainforge.iam.client.internal;

import feign.Client;
import feign.Logger;
import feign.Request;
import feign.Retryer;
import feign.hc5.ApacheHttp5Client;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.config.TlsConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.core5.util.Timeout;
import org.domainforge.access.api.AccessException;
import org.domainforge.access.api.AccessFailure;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.cloud.openfeign.loadbalancer.FeignBlockingLoadBalancerClient;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/** 只属于 IAM 这个 Feign ensemble，不向宿主其他 Feign 客户端分发证书或认证头。 */
public class IamFeignConfiguration {
  /** IAM 不继承宿主的全局认证拦截器、重试或日志设置。 */
  @Bean
  org.springframework.cloud.openfeign.clientconfig.FeignClientConfigurer iamClientConfigurer() {
    return new org.springframework.cloud.openfeign.clientconfig.FeignClientConfigurer() {
      @Override
      public boolean inheritParentConfiguration() {
        return false;
      }
    };
  }

  /** 原生 SSL bundle、有限池与逐阶段超时；容器关闭时释放连接池。 */
  @Bean(destroyMethod = "close")
  CloseableHttpClient iamHttpClient(SslBundles bundles, Environment env) {
    var tls =
        bundles
            .getBundle(env.getProperty("domainforge.iam-client.ssl-bundle", "iam-client"))
            .createSslContext();
    var wire =
        org.apache.hc.core5.http.config.Http1Config.custom()
            .setMaxLineLength(8192)
            .setMaxHeaderCount(100)
            .build();
    var pool =
        PoolingHttpClientConnectionManagerBuilder.create()
            .setConnectionFactory(
                org.apache.hc.client5.http.impl.io.ManagedHttpClientConnectionFactory.builder()
                    .http1Config(wire)
                    .build())
            .setMaxConnTotal(20)
            .setMaxConnPerRoute(20)
            .setTlsSocketStrategy(
                ClientTlsStrategyBuilder.create().setSslContext(tls).buildClassic())
            .setDefaultTlsConfig(
                TlsConfig.custom().setHandshakeTimeout(Timeout.ofMilliseconds(300)).build())
            .setDefaultConnectionConfig(
                ConnectionConfig.custom()
                    .setConnectTimeout(Timeout.ofMilliseconds(300))
                    .setSocketTimeout(Timeout.ofSeconds(1))
                    .build())
            .build();
    return HttpClients.custom()
        .setConnectionManager(pool)
        .disableAutomaticRetries()
        .disableRedirectHandling()
        .disableCookieManagement()
        .disableAuthCaching()
        .disableContentCompression()
        .setDefaultRequestConfig(
            RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(200))
                .setResponseTimeout(Timeout.ofSeconds(1))
                .build())
        .build();
  }

  /** 只向最终 HTTPS 当前事实接口发送凭证，不接受附加凭据或参数。 */
  private void validateEndpoint(Request request) {
    var uri = java.net.URI.create(request.url());
    if (!"https".equals(uri.getScheme())
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || !"/internal/access/current".equals(uri.getPath())
        || request.httpMethod() != Request.HttpMethod.GET)
      throw new AccessException(AccessFailure.UNAVAILABLE);
  }

  /** 原生负载均衡和URL配置解包都经过同一目标校验，HTTP执行仍交给HC5。 */
  @Bean
  Client iamFeignClient(
      @org.springframework.beans.factory.annotation.Qualifier("iamHttpClient")
          CloseableHttpClient client,
      LoadBalancerClient loadBalancer,
      LoadBalancerClientFactory factory) {
    var nativeClient = new ApacheHttp5Client(client);
    Client guarded =
        (request, options) -> {
          validateEndpoint(request);
          return nativeClient.execute(request, options);
        };
    return new FeignBlockingLoadBalancerClient(guarded, loadBalancer, factory, List.of());
  }

  /** 鉴权读取不自动重试。 */
  @Bean
  Retryer iamRetryer() {
    return Retryer.NEVER_RETRY;
  }

  /** 不跟随重定向，避免向另一地址转发 Bearer。 */
  @Bean
  Request.Options iamOptions() {
    return new Request.Options(300, TimeUnit.MILLISECONDS, 1000, TimeUnit.MILLISECONDS, false);
  }

  /** HTTP 请求含令牌，原生 Feign 日志固定关闭。 */
  @Bean
  Logger.Level iamLogLevel() {
    return Logger.Level.NONE;
  }
}

package org.domainforge.boot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 装配首个工程的回环 HTTP 健康服务；当前不启用业务接口或外部中间件。 */
@SpringBootApplication(proxyBeanMethods = false)
public class DomainForgeApplication {

  /**
   * 启动应用并交由框架管理生命周期；非法或占用端口使启动失败。
   *
   * @param args 框架启动参数；受支持的运行方式使用回环监听地址
   */
  public static void main(String[] args) {
    SpringApplication.run(DomainForgeApplication.class, args);
  }
}

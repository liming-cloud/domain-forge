package org.domainforge.iam.bootstrap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 只在 Web 服务进程启用调度（数据保留清理）；初始化命令等维护进程不启动后台任务。
 *
 * <p>后端使用 HTTP，TLS 与证书由 Nginx 等入口负责；通用访问执行由 access-control 组件自动装配。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableScheduling
public class IamHttpConfiguration {
}

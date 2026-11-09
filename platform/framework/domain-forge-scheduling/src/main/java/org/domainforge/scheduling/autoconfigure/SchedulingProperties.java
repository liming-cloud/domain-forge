package org.domainforge.scheduling.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 调度配置（前缀 {@code domainforge.scheduling}）。启用后 {@code serverAddress} 与 {@code appPassword} 在登记任务时必填。
 *
 * @param enabled       设为 true 启用调度（仅 Web 应用生效）
 * @param serverAddress PowerJob 服务地址
 * @param appName       PowerJob 应用名，留空为 spring.application.name
 * @param appPassword   PowerJob 应用密码
 * @param workerPort    worker 通信端口
 */
@ConfigurationProperties("domainforge.scheduling")
public record SchedulingProperties(
        boolean enabled,
        String serverAddress,
        String appName,
        String appPassword,
        @DefaultValue("27777") int workerPort) {
}

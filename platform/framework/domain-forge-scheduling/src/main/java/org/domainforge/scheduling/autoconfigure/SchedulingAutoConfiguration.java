package org.domainforge.scheduling.autoconfigure;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import org.domainforge.scheduling.api.ScheduledTask;
import org.domainforge.scheduling.internal.TaskDispatcher;
import org.domainforge.scheduling.internal.TaskRegistrar;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import tech.powerjob.client.PowerJobClient;

import java.util.List;

/**
 * 定时任务装配：启用时注册统一处理器并在启动后登记任务；未启用时，部署环境（config-center）中声明了任务即启动失败。
 */
@AutoConfiguration
@ConditionalOnWebApplication
public class SchedulingAutoConfiguration {
    /**
     * 启用调度时的装配。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "domainforge.scheduling.enabled", havingValue = "true")
    static class Enabled {
        /**
         * PowerJob 按 Bean 名找到的处理器。
         */
        @Bean(TaskDispatcher.BEAN)
        TaskDispatcher domainforgeTaskDispatcher(ObjectProvider<ScheduledTask> tasks,
                                                 ObjectProvider<MeterRegistry> metrics,
                                                 ObjectProvider<Tracer> tracer) {
            return new TaskDispatcher(tasks.orderedStream().toList(), metrics.getIfAvailable(SimpleMeterRegistry::new),
                    tracer.getIfAvailable(() -> Tracer.NOOP));
        }

        /**
         * 启动完成后登记任务；失败使启动失败。
         */
        @Bean
        ApplicationRunner scheduledTaskRegistration(ObjectProvider<ScheduledTask> tasks, Environment environment) {
            return arguments -> {
                List<ScheduledTask> all = tasks.orderedStream().toList();
                if (all.isEmpty()) return;
                try (var client = new PowerJobClient(
                        environment.getRequiredProperty("domainforge.scheduling.server-address"),
                        environment.getProperty("domainforge.scheduling.app-name",
                                environment.getRequiredProperty("spring.application.name")),
                        environment.getRequiredProperty("domainforge.scheduling.app-password"))) {
                    new TaskRegistrar(all, client).register();
                }
            };
        }
    }

    /**
     * 未启用调度时：部署环境中声明了任务就启动失败，避免任务静默不运行。
     */
    @Bean
    @ConditionalOnProperty(name = "domainforge.scheduling.enabled", havingValue = "false", matchIfMissing = true)
    SchedulingDisabled schedulingDisabled(ObjectProvider<ScheduledTask> tasks, Environment environment) {
        if (environment.matchesProfiles("config-center") && tasks.stream().findAny().isPresent())
            throw new IllegalStateException("部署环境声明了定时任务，但未启用调度（domainforge.scheduling.enabled）");
        return new SchedulingDisabled();
    }

    /**
     * 调度未启用的标记。
     */
    static final class SchedulingDisabled {
    }
}

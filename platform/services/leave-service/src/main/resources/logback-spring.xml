<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <!-- 有状态监听器时，Boot 不把 Logback 的 WARN 状态（如重新配置间隙中其他线程写日志留下的“No appenders present”）
         以文本打到标准输出；ERROR 状态仍由 Boot 汇总并使启动失败。 -->
    <statusListener class="ch.qos.logback.core.status.NopStatusListener"/>
    <include resource="org/springframework/boot/logging/logback/defaults.xml"/>
    <!-- 部署环境（config-center）向标准输出写 ECS 结构化 JSON 日志：时间、级别、服务名、traceId、日志器与消息。 -->
    <springProfile name="config-center">
        <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
            <encoder class="org.springframework.boot.logging.logback.StructuredLogEncoder">
                <format>ecs</format>
                <charset>UTF-8</charset>
            </encoder>
        </appender>
    </springProfile>
    <!-- 本地开发为 Boot 默认的文本格式（含 traceId-spanId 关联字段）。 -->
    <springProfile name="!config-center">
        <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
            <encoder>
                <pattern>${CONSOLE_LOG_PATTERN}</pattern>
                <charset>${CONSOLE_LOG_CHARSET}</charset>
            </encoder>
        </appender>
    </springProfile>
    <appender name="ASYNC" class="ch.qos.logback.classic.AsyncAppender">
        <queueSize>256</queueSize>
        <discardingThreshold>0</discardingThreshold>
        <neverBlock>true</neverBlock>
        <maxFlushTime>1000</maxFlushTime>
        <appender-ref ref="CONSOLE"/>
    </appender>
    <root level="INFO">
        <appender-ref ref="ASYNC"/>
    </root>
</configuration>

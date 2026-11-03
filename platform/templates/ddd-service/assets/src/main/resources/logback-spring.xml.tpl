<?xml version="1.0" encoding="UTF-8"?>
<configuration>
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

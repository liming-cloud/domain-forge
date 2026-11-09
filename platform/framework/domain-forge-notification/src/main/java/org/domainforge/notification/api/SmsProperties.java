package org.domainforge.notification.api;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * 短信配置（前缀 {@code domainforge.sms}）。凭据与签名在创建默认（阿里云）实现时才检查，业务自带 {@link SmsSender} 时可以不配。
 *
 * @param enabled        设为 true 启用短信发送
 * @param provider       默认实现支持的服务商，目前只有 aliyun
 * @param signName       短信签名
 * @param templates      业务模板名到服务商模板编号的映射
 * @param connectTimeout 连接超时
 * @param requestTimeout 请求超时
 * @param aliyun         阿里云接入参数
 */
@ConfigurationProperties("domainforge.sms")
public record SmsProperties(
        boolean enabled,
        @DefaultValue("aliyun") String provider,
        @DefaultValue("") String signName,
        Map<String, String> templates,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("5s") Duration requestTimeout,
        @DefaultValue Aliyun aliyun) {
    /**
     * 未声明模板时为空映射。
     */
    public SmsProperties {
        if (templates == null) templates = Map.of();
    }

    /**
     * 阿里云接入参数。
     *
     * @param endpoint        接口地址
     * @param accessKeyId     访问凭据标识
     * @param accessKeySecret 访问凭据密钥
     */
    public record Aliyun(
            @DefaultValue("https://dysmsapi.aliyuncs.com") URI endpoint,
            @DefaultValue("") String accessKeyId,
            @DefaultValue("") String accessKeySecret) {
    }
}

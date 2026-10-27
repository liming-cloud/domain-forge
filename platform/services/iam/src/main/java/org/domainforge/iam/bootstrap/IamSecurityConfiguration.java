package org.domainforge.iam.bootstrap;

import cn.dev33.satoken.stp.StpLogic;
import io.micrometer.core.instrument.MeterRegistry;
import org.domainforge.iam.application.port.authentication.NativeSessions;
import org.domainforge.iam.application.port.authentication.SecretOperations;
import org.domainforge.config.api.DynamicSettings;
import org.domainforge.config.api.DynamicSettingsBinder;
import org.domainforge.iam.infrastructure.configuration.LoginProtectionSettings;
import org.domainforge.iam.infrastructure.configuration.PasswordHashingSettings;
import org.domainforge.iam.infrastructure.security.ArgonPasswords;
import org.domainforge.iam.infrastructure.security.JcaSecretOperations;
import org.domainforge.iam.infrastructure.security.SaNativeSessions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Base64;

/**
 * 只装配需要秘密输入、资源关闭或原生生命周期的安全对象。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PasswordHashingSettings.class)
public class IamSecurityConfiguration {
    /**
     * 无默认指纹密钥，非法部署输入拒绝启动。
     */
    @Bean
    public SecretOperations secrets(@Value("${iam.fingerprint-key}") String key) {
        return new JcaSecretOperations(Base64.getDecoder().decode(key));
    }

    /**
     * 有界密码工作池随应用关闭，指标仅观察实际计算及排队。
     */
    @Bean(destroyMethod = "close")
    public ArgonPasswords passwords(MeterRegistry metrics, PasswordHashingSettings settings) {
        var passwords = new ArgonPasswords(settings);
        metrics.gauge("iam.password.active", passwords, ArgonPasswords::activeComputations);
        metrics.gauge("iam.password.queued", passwords, ArgonPasswords::queuedComputations);
        return passwords;
    }

    /**
     * 登录防护阈值是动态配置组：启用配置中心时随 Nacos 发布生效，无法应用的发布保留当前值。
     */
    @Bean
    public DynamicSettings<LoginProtectionSettings> loginProtectionSettings(DynamicSettingsBinder binder) {
        return binder.bind("iam.login-protection", LoginProtectionSettings.class);
    }

    /**
     * 会话生命周期使用公共组件装配的唯一普通原生逻辑，不反向依赖当前事实源。
     */
    @Bean
    public NativeSessions nativeSessions(StpLogic logic, MeterRegistry metrics) {
        return new SaNativeSessions(logic, metrics);
    }
}

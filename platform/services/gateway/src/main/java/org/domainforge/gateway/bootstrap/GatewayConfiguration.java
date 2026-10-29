package org.domainforge.gateway.bootstrap;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoForRedisson;
import cn.dev33.satoken.reactor.filter.SaReactorFilter;
import cn.dev33.satoken.stp.StpLogic;
import org.domainforge.config.api.DynamicSettings;
import org.domainforge.config.api.DynamicSettingsBinder;
import org.domainforge.gateway.infrastructure.filter.LoginCheck;
import org.domainforge.gateway.infrastructure.routing.GatewaySettings;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 网关装配：路由配置组，以及与后端相同的 Sa-Token 会话存储（共用会话 Redis，键格式由同样的 sa-token 配置决定）。
 */
@Configuration(proxyBeanMethods = false)
public class GatewayConfiguration {
    /**
     * 路由与限流的动态配置组 gateway.routing；启动时非法即启动失败，运行中非法发布保持当前值。
     *
     * <p>不直接用 gateway 作前缀：环境变量 GATEWAY_* 会按宽松绑定映射到 gateway.*，被整组绑定当作未知键。
     */
    @Bean
    DynamicSettings<GatewaySettings> gatewaySettings(DynamicSettingsBinder binder) {
        return binder.bind("gateway.routing", GatewaySettings.class);
    }

    /**
     * 官方 Redisson DAO，与 cache 组件共用同一个客户端。
     */
    @Bean
    SaTokenDao gatewaySessionStore(RedissonClient redisson) {
        return new SaTokenDaoForRedisson(redisson);
    }

    /**
     * 登录前置校验：Sa-Token 官方的全局过滤器，排在路径检查、客户端地址与限流之后。
     */
    @Bean
    SaReactorFilter gatewayLoginCheck(DynamicSettings<GatewaySettings> settings) {
        return LoginCheck.filter(settings);
    }

    /**
     * 与后端相同的登录类型，只用于查询会话是否存在。
     */
    @Bean
    StpLogic gatewayStpLogic() {
        return new StpLogic("login");
    }
}

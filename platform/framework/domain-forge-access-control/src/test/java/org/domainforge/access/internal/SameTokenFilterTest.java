package org.domainforge.access.internal;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.filter.SaTokenContextFilterForJakartaServlet;
import cn.dev33.satoken.same.SaSameUtil;
import org.domainforge.access.api.AccessException;
import org.domainforge.access.api.AccessFailure;
import org.domainforge.access.api.SameTokens;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 Boot 装配下的 Same-Token 校验：直连拒绝、轮换窗口、存储故障，以及校验方不写入新值。
 */
class SameTokenFilterTest {
    private static ConfigurableApplicationContext start(String enabled) {
        return new SpringApplicationBuilder(NativeAccessIntegrationTest.Application.class).run(
                "--server.port=0",
                "--spring.main.banner-mode=off",
                "--logging.level.root=ERROR",
                "--domainforge.access.session-store=memory",
                "--domainforge.access.same-token.enabled=" + enabled,
                "--spring.autoconfigure.exclude=org.redisson.spring.starter.RedissonAutoConfigurationV2,"
                        + "org.redisson.spring.starter.RedissonAutoConfigurationV4");
    }

    private static MockMvc mvc(ConfigurableApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup((WebApplicationContext) context)
                .addFilters(context.getBean(SameTokenFilter.class), new SaTokenContextFilterForJakartaServlet())
                .build();
    }

    private static int call(MockMvc mvc, String sameToken) throws Exception {
        var request = MockMvcRequestBuilders.get("/anonymous");
        if (sameToken != null) request.header(SameTokens.HEADER, sameToken);
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    // 存储读取一律失败，模拟会话 Redis 不可用。
    private static SaTokenDao outage(SaTokenDao original) {
        return (SaTokenDao) Proxy.newProxyInstance(
                SameTokenFilterTest.class.getClassLoader(),
                new Class<?>[]{SaTokenDao.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("get")) throw new IllegalStateException("synthetic-outage");
                    try {
                        return method.invoke(original, arguments);
                    } catch (InvocationTargetException error) {
                        throw error.getCause();
                    }
                });
    }

    // 尚无 Same-Token 时直连被拒，且校验不会替攻击者生成一个值。
    @Test
    void verifierNeverCreatesToken() throws Exception {
        try (var context = start("true")) {
            var mvc = mvc(context);
            // 原生内存存储在进程内共享，先清除其他用例留下的值。
            var store = SaManager.getSaTokenDao();
            store.delete(SaManager.getSaSameTemplate().splicingTokenSaveKey());
            store.delete(SaManager.getSaSameTemplate().splicingPastTokenSaveKey());
            assertEquals(401, call(mvc, null));
            assertEquals(401, call(mvc, "guess"));
            assertNull(SaSameUtil.getTokenNh());
        }
    }

    // 缺失、错误值 401；当前值通过；轮换后上一个值在窗口内仍通过，更早的值被拒。
    @Test
    void acceptsCurrentAndPreviousValueOnly() throws Exception {
        try (var context = start("true")) {
            var mvc = mvc(context);
            String first = SameTokens.current();
            assertEquals(200, call(mvc, first));
            assertEquals(401, call(mvc, null));
            assertEquals(401, call(mvc, first + "x"));
            String second = SaSameUtil.refreshToken();
            assertEquals(200, call(mvc, second));
            assertEquals(200, call(mvc, first));
            SaSameUtil.refreshToken();
            assertEquals(401, call(mvc, first));
        }
    }

    // 存储不可用时校验方返回 503，调用方取值报告 UNAVAILABLE，都不放行。
    @Test
    void storeOutageIsUnavailable() throws Exception {
        try (var context = start("true")) {
            var mvc = mvc(context);
            String token = SameTokens.current();
            var original = SaManager.getSaTokenDao();
            try {
                SaManager.setSaTokenDao(outage(original));
                assertEquals(503, call(mvc, token));
                var failure = assertThrows(AccessException.class, SameTokens::current);
                assertEquals(AccessFailure.UNAVAILABLE, failure.failure());
            } finally {
                SaManager.setSaTokenDao(original);
            }
        }
    }

    // 显式关闭时不注册过滤器（仅用于网关接入前的部署）。
    @Test
    void disabledRegistersNoFilter() {
        try (var context = start("false")) {
            assertTrue(context.getBeansOfType(SameTokenFilter.class).isEmpty());
        }
    }
}

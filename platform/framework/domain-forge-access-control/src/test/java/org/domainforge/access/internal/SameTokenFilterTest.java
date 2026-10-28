package org.domainforge.access.internal;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.filter.SaServletFilter;
import cn.dev33.satoken.filter.SaTokenContextFilterForJakartaServlet;
import cn.dev33.satoken.same.SaSameUtil;
import org.domainforge.access.api.AccessException;
import org.domainforge.access.api.AccessFailure;
import org.domainforge.access.api.SameTokens;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 Boot 装配下按 Sa-Token 官方用法（SaServletFilter + checkCurrentRequestToken）的 Same-Token 校验：
 * 直连拒绝、轮换窗口、存储故障、部署环境不能关闭。
 */
class SameTokenFilterTest {
    private static ConfigurableApplicationContext start(String enabled, String... extra) {
        var arguments = new ArrayList<>(List.of(
                "--server.port=0",
                "--spring.main.banner-mode=off",
                "--logging.level.root=ERROR",
                "--domainforge.access.session-store=memory",
                "--domainforge.access.same-token.enabled=" + enabled,
                "--spring.autoconfigure.exclude=org.redisson.spring.starter.RedissonAutoConfigurationV2,"
                        + "org.redisson.spring.starter.RedissonAutoConfigurationV4"));
        arguments.addAll(List.of(extra));
        return new SpringApplicationBuilder(NativeAccessIntegrationTest.Application.class)
                .run(arguments.toArray(String[]::new));
    }

    private static MockMvc mvc(ConfigurableApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup((WebApplicationContext) context)
                .addFilters(new SaTokenContextFilterForJakartaServlet(), sameTokenFilter(context))
                .build();
    }

    @SuppressWarnings("unchecked")
    private static SaServletFilter sameTokenFilter(ConfigurableApplicationContext context) {
        return ((FilterRegistrationBean<SaServletFilter>) context.getBean("sameTokenFilter")).getFilter();
    }

    private static int call(MockMvc mvc, String sameToken) throws Exception {
        return call(mvc, "/anonymous", sameToken);
    }

    private static int call(MockMvc mvc, String path, String sameToken) throws Exception {
        var request = MockMvcRequestBuilders.get(path);
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

    // 缺失或伪造的值被拒，响应为平台外壳且不回显收到的值。
    @Test
    void missingOrForgedTokenIsRejected() throws Exception {
        try (var context = start("true")) {
            var mvc = mvc(context);
            assertEquals(401, call(mvc, null));
            var forged = mvc.perform(MockMvcRequestBuilders.get("/anonymous").header(SameTokens.HEADER, "guess"))
                    .andReturn().getResponse();
            assertEquals(401, forged.getStatus());
            assertTrue(forged.getContentAsString().contains("\"code\":\"UNAUTHENTICATED\""));
            assertFalse(forged.getContentAsString().contains("guess"));
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

    // 服务专用接口（/internal/** 上的 @SaIgnore）：无用户会话，只凭 Same-Token 调用；没有 Same-Token 即使匿名也被拒。
    @Test
    void serviceOnlyEndpointNeedsSameTokenOnly() throws Exception {
        try (var context = start("true")) {
            var mvc = mvc(context);
            assertEquals(200, call(mvc, "/internal/service-only", SameTokens.current()));
            assertEquals(401, call(mvc, "/internal/service-only", null));
        }
    }

    // 本地开发与测试可以显式关闭；部署环境（config-center）关闭即启动失败。
    @Test
    void disablingIsLocalOnly() {
        try (var context = start("false")) {
            assertFalse(context.containsBean("sameTokenFilter"));
        }
        var failure = assertThrows(RuntimeException.class,
                () -> start("false", "--spring.profiles.active=config-center"));
        assertTrue(rootCause(failure).getMessage().contains("不得关闭 Same-Token"));
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause;
    }
}

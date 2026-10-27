package org.domainforge.web.internal;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 请求标识只在本次请求内进入日志上下文；非法传入值不进入日志。
 */
class RequestIdFilterTest {
    private static String filter(String supplied, AtomicReference<String> seen) throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/anything");
        if (supplied != null) request.addHeader(RequestIdFilter.HEADER, supplied);
        var response = new MockHttpServletResponse();
        new RequestIdFilter().doFilter(request, response, (input, output) -> seen.set(MDC.get("requestId")));
        assertNull(MDC.get("requestId"));
        return response.getHeader(RequestIdFilter.HEADER);
    }

    // 入口给出的合法标识原样沿用，日志上下文与响应头一致，请求结束后清除。
    @Test
    void keepsSuppliedIdentifierForOneRequest() throws Exception {
        var seen = new AtomicReference<String>();
        assertEquals("abc-123.X_y", filter("abc-123.X_y", seen));
        assertEquals("abc-123.X_y", seen.get());
    }

    // 缺失、含换行或超长的值不能进入日志，改为生成新标识。
    @Test
    void replacesMissingOrUnsafeIdentifier() throws Exception {
        for (String supplied : new String[]{null, "", "a\nforged", "x".repeat(65), "带中文"}) {
            var seen = new AtomicReference<String>();
            String id = filter(supplied, seen);
            assertTrue(id.matches("[0-9a-f]{32}"), String.valueOf(supplied));
            assertEquals(id, seen.get());
        }
    }
}

package org.domainforge.idempotency.internal;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 过滤器自身的拒绝路径：记录存储故障 503 且不执行业务（不退化为不做幂等）、键格式 400、请求体超限 413。
 */
class IdempotencyFilterTest {
    private static final IdempotencyFilter.Settings SETTINGS =
            new IdempotencyFilter.Settings("app", Duration.ofHours(1), Duration.ofSeconds(5), 16, 1024);

    private static IdempotencyFilter filter(IdempotencyRecords records) {
        var endpoints = new IdempotentEndpoints(List.of()) {
            @Override
            boolean matches(String method, String path) {
                return true;
            }
        };
        return new IdempotencyFilter(endpoints, records, () -> 7L, SETTINGS);
    }

    private static MockHttpServletRequest request(String key, String body) {
        var request = new MockHttpServletRequest("POST", "/api/orders");
        request.addHeader("Idempotency-Key", key);
        request.setContent(body.getBytes());
        return request;
    }

    @Test
    void storeOutageRejectsWithoutExecuting() throws Exception {
        var failing = new IdempotencyRecords(null) {
            @Override
            Outcome begin(String key, String fingerprint, Duration lease) {
                throw new IllegalStateException("Redis 不可用");
            }
        };
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter(failing).doFilter(request("k-1", "{}"), response, chain);
        assertEquals(503, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"code\":\"SERVICE_UNAVAILABLE\""));
        assertNull(chain.getRequest(), "存储不可用时不能执行业务");
    }

    @Test
    void invalidKeyAndOversizedBody() throws Exception {
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter(new IdempotencyRecords(null)).doFilter(request("has space", "{}"), response, chain);
        assertEquals(400, response.getStatus());
        assertNull(chain.getRequest());
        var large = new MockHttpServletResponse();
        filter(new IdempotencyRecords(null)).doFilter(request("k-2", "x".repeat(17)), large, new MockFilterChain());
        assertEquals(413, large.getStatus());
        assertTrue(large.getContentAsString().contains("\"code\":\"PAYLOAD_TOO_LARGE\""));
    }
}

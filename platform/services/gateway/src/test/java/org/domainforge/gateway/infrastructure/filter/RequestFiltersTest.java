package org.domainforge.gateway.infrastructure.filter;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 路径检查、请求标识与客户端地址的判定规则。
 */
class RequestFiltersTest {
    // 可能被后端解码或规范化成其他路径的写法一律拒绝。
    @Test
    void pathGuardRejectsAmbiguousPaths() {
        for (String path : List.of("/api/%2e%2e/internal/access/current", "/api/%2E./x", "/api/a%2Fb",
                "/api/a%5cb", "/api/..;/internal", "/api/a;x=1", "/api/./x", "/api/../internal", "//api/x",
                "/api/a%252e", "/api/a\\b"))
            assertFalse(PathGuardFilter.safe(path), path);
        for (String path : List.of("/api/notes", "/api/notes/1", "/api/me/notes", "/api/a.b/c-d_e"))
            assertTrue(PathGuardFilter.safe(path), path);
    }

    // 合法标识沿用，含换行或超长的值重新生成。
    @Test
    void requestIdKeepsOnlySafeValues() {
        assertEquals("abc-1.2_x", RequestIdentityFilter.requestId("abc-1.2_x"));
        for (String supplied : new String[]{null, "", "a\nb", "x".repeat(65)})
            assertTrue(RequestIdentityFilter.requestId(supplied).matches("[0-9a-f]{32}"));
    }

    // 只有可信代理给出的转发地址被采用；伪造的头和非地址内容退回连接地址。
    @Test
    void clientAddressTrustsOnlyKnownProxy() {
        var filter = new RequestIdentityFilter(List.of("172.31.250.10"));
        assertEquals("203.0.113.7", filter.clientAddress("172.31.250.10", "203.0.113.7"));
        assertEquals("203.0.113.7", filter.clientAddress("172.31.250.10", "10.0.0.1, 203.0.113.7"));
        assertEquals("198.51.100.2", filter.clientAddress("198.51.100.2", "203.0.113.7"));
        assertEquals("172.31.250.10", filter.clientAddress("172.31.250.10", "evil\nvalue"));
        assertEquals("198.51.100.2", new RequestIdentityFilter(List.of()).clientAddress("198.51.100.2", "1.2.3.4"));
    }

    // 连接类故障沿原因链识别为依赖不可用。
    @Test
    void connectionFailuresAreRecognised() {
        assertTrue(GatewayErrorHandler.connectionFailure(new IOException(new ConnectException("refused"))));
        assertFalse(GatewayErrorHandler.connectionFailure(new IllegalStateException("other")));
    }
}

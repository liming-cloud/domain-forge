package org.domainforge.servicecall.internal;

import feign.Capability;
import feign.Client;
import org.domainforge.servicecall.api.ServiceCallException;
import org.domainforge.servicecall.api.ServiceCallFailure;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * 把传输层异常翻译为跨服务调用失败：没有建立连接的为 UNAVAILABLE（请求确定未发出），
 * 已发出后超时或中断的为 UNKNOWN（远端可能已经执行），不交给 Feign 包装成可重试异常。
 */
public final class TransportFailures implements Capability {
    /**
     * 判断异常是否发生在建立连接之前。
     */
    static boolean beforeSending(IOException failure) {
        if (failure instanceof ConnectException || failure instanceof UnknownHostException
                || failure instanceof NoRouteToHostException) return true;
        return failure instanceof SocketTimeoutException timeout && timeout.getMessage() != null
                && timeout.getMessage().toLowerCase(Locale.ROOT).contains("connect");
    }

    @Override
    public Client enrich(Client client) {
        return (request, options) -> {
            try {
                return client.execute(request, options);
            } catch (IOException failure) {
                var kind = beforeSending(failure) ? ServiceCallFailure.UNAVAILABLE : ServiceCallFailure.UNKNOWN;
                throw new ServiceCallException(kind, null, failure);
            }
        };
    }
}

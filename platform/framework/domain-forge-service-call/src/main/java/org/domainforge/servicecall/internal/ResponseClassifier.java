package org.domainforge.servicecall.internal;

import feign.Response;
import feign.codec.ErrorDecoder;
import org.domainforge.servicecall.api.ServiceCallException;
import org.domainforge.servicecall.api.ServiceCallFailure;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * 非 2xx 响应的分类：401、403 保持语义；其他 4xx 为对方的拒绝（附带外壳中的代码）；504 结果未知；其余为不可用。
 *
 * <p>只读取响应体前 4 KiB 提取代码，不记录或传递对方的提示文本。
 */
public final class ResponseClassifier implements ErrorDecoder {
    private static final int BODY_LIMIT = 4096;
    private static final Pattern CODE = Pattern.compile("\"code\"\\s*:\\s*\"([A-Z][A-Z0-9_]{0,63})\"");

    /**
     * 按状态码分类。
     */
    static ServiceCallFailure classify(int status) {
        if (status == 401) return ServiceCallFailure.UNAUTHENTICATED;
        if (status == 403) return ServiceCallFailure.FORBIDDEN;
        if (status == 504) return ServiceCallFailure.UNKNOWN;
        if (status >= 400 && status < 500) return ServiceCallFailure.REJECTED;
        return ServiceCallFailure.UNAVAILABLE;
    }

    @Override
    public Exception decode(String methodKey, Response response) {
        ServiceCallFailure failure = classify(response.status());
        String code = failure == ServiceCallFailure.REJECTED ? code(response) : null;
        return new ServiceCallException(failure, code, null);
    }

    private static String code(Response response) {
        if (response.body() == null) return null;
        try (InputStream body = response.body().asInputStream()) {
            var matcher = CODE.matcher(new String(body.readNBytes(BODY_LIMIT), StandardCharsets.UTF_8));
            return matcher.find() ? matcher.group(1) : null;
        } catch (IOException unreadable) {
            return null;
        }
    }
}

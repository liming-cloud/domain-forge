package org.domainforge.web.api;

import jakarta.servlet.http.HttpServletResponse;
import org.domainforge.shared.BusinessRuleViolation;
import org.domainforge.shared.DependencyUnavailableException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.logging.Logger;

/**
 * 失败的唯一分派点：业务规则违反按类别返回 4xx，依赖不可用返回 503，其余一律 500；响应体为 RFC 9457 Problem Details，
 * 稳定错误码放在扩展字段 {@code code}。
 *
 * <p>组件和服务不注册自己的全局异常处理；需要自带处理器的入口（如 IAM）也用 {@link #dispatch(Exception)} 取得同一结果。
 */
public final class Problems {
    private static final Logger LOGGER = Logger.getLogger(Problems.class.getName());
    private static final List<FailureTranslator> TRANSLATORS = loadTranslators();
    private static final int MAX_CAUSE_DEPTH = 4;
    private static final int MAX_FRAMES = 8;
    private static final int MAX_DIAGNOSTIC = 4096;

    private Problems() {
    }

    private static List<FailureTranslator> loadTranslators() {
        List<FailureTranslator> found = new ArrayList<>();
        ServiceLoader.load(FailureTranslator.class, Problems.class.getClassLoader()).forEach(found::add);
        return List.copyOf(found);
    }

    /**
     * 按失败类型确定协议结果；不读取未知异常的消息，原因只写入内部诊断。
     *
     * @param original 入口捕获的异常
     * @return 固定状态、稳定错误码和安全提示的 Problem Details
     */
    public static ResponseEntity<ProblemDetail> dispatch(Exception original) {
        Exception failure = translate(original);
        if (failure instanceof BusinessRuleViolation violation) {
            return of(status(violation.kind()), violation.code(), violation.getMessage());
        }
        if (failure instanceof DependencyUnavailableException) {
            LOGGER.severe(() -> diagnostic("dependency-unavailable", failure));
            return of(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "服务暂不可用");
        }
        if (failure instanceof MaxUploadSizeExceededException) {
            return of(HttpStatus.CONTENT_TOO_LARGE, "PAYLOAD_TOO_LARGE", "请求内容过大");
        }
        if (failure instanceof HttpMessageNotReadableException
                || failure instanceof TypeMismatchException
                || failure instanceof ServletRequestBindingException
                || failure instanceof MultipartException) {
            return of(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请求不合法");
        }
        LOGGER.severe(() -> diagnostic("unhandled", failure));
        return of(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务处理失败");
    }

    private static Exception translate(Exception failure) {
        for (FailureTranslator translator : TRANSLATORS) {
            RuntimeException translated = translator.translate(failure);
            if (translated != null) {
                return translated;
            }
        }
        return failure;
    }

    /**
     * 构造带稳定错误码的 Problem Details 响应。
     *
     * @param status 状态
     * @param code   稳定错误码
     * @param detail 可安全展示的说明
     * @return 响应
     */
    public static ResponseEntity<ProblemDetail> of(HttpStatusCode status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return ResponseEntity.status(status).body(problem);
    }

    /**
     * 在 Filter 等没有 MVC 异常处理的位置写出同样的响应；调用方负责不回显请求内容。
     *
     * @param response Servlet 响应
     * @param status   状态
     * @param code     稳定错误码
     * @param detail   可安全展示的说明
     * @throws IOException 写响应失败
     */
    public static void write(HttpServletResponse response, int status, String code, String detail) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(json(status, code, detail));
    }

    /**
     * 与 {@link #write} 相同的 JSON 文本，供非 Servlet 写出方使用。
     *
     * @param status 状态
     * @param code   稳定错误码
     * @param detail 可安全展示的说明
     * @return JSON 文本
     */
    public static String json(int status, String code, String detail) {
        String title = HttpStatus.resolve(status) == null ? "" : HttpStatus.resolve(status).getReasonPhrase();
        return "{\"type\":\"about:blank\",\"title\":\"" + escape(title) + "\",\"status\":" + status
                + ",\"detail\":\"" + escape(detail) + "\",\"code\":\"" + escape(code) + "\"}";
    }

    private static HttpStatus status(BusinessRuleViolation.Kind kind) {
        return switch (kind) {
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case PAYLOAD_TOO_LARGE -> HttpStatus.CONTENT_TOO_LARGE;
            case UNPROCESSABLE -> HttpStatus.UNPROCESSABLE_CONTENT;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
        };
    }

    private static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /**
     * 仅保留故障类别、异常类型和代码位置，不读取 message、suppressed 或序列化原始异常。最多展开四层原因、每层八个栈位置；
     * 截断显式标记，避免循环原因或长栈放大日志。
     */
    private static String diagnostic(String kind, Throwable failure) {
        StringBuilder text = new StringBuilder("platform_http_failure kind=").append(kind);
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++) {
            text.append(" cause=").append(cause.getClass().getName());
            StackTraceElement[] stack = cause.getStackTrace();
            for (int index = 0; index < Math.min(stack.length, MAX_FRAMES); index++) {
                StackTraceElement frame = stack[index];
                text.append(" at=")
                        .append(frame.getClassName())
                        .append('.')
                        .append(frame.getMethodName())
                        .append(':')
                        .append(frame.getLineNumber());
            }
            if (stack.length > MAX_FRAMES) {
                text.append(" stack-truncated");
            }
            cause = cause.getCause();
        }
        if (cause != null) {
            text.append(" cause-truncated");
        }
        return text.length() <= MAX_DIAGNOSTIC ? text.toString()
                : text.substring(0, MAX_DIAGNOSTIC - 10) + " truncated";
    }
}

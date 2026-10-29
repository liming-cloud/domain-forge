package org.domainforge.idempotency.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标在入口方法上：请求带 {@code Idempotency-Key} 头时，同一账号对同一接口、同一键的请求只执行一次。
 *
 * <p>重复请求原样返回首次的状态码与响应体（带 {@code Idempotent-Replayed: true}）；首次仍在处理时 409
 * {@code IDEMPOTENCY_IN_PROGRESS}；同一键但请求体不同 422 {@code IDEMPOTENCY_KEY_REUSED}。首次返回 5xx 时不保存，
 * 允许用同一键重试。不带该头的请求照常处理。只用于已登录的接口；未登录的请求照常交给入口拒绝。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {
}

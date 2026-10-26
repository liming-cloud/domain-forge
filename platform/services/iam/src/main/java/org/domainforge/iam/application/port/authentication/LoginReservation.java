package org.domainforge.iam.application.port.authentication;

/**
 * 一次登录预占的标识；成功只移除自己的账号预占，不清除其他失败。
 *
 * @param accountKey 账号计数键（HMAC 后的登录名），不含原始登录名
 * @param attemptId  本次尝试的随机标识
 */
public record LoginReservation(String accountKey, String attemptId) {
}

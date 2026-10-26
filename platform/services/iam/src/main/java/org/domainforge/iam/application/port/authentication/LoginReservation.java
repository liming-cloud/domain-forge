package org.domainforge.iam.application.port.authentication;

/**
 * 一次登录预占标识；成功只移除自己的账号预占，不清除其他失败。
 */
public record LoginReservation(String accountKey, String attemptId, String generation) {
}

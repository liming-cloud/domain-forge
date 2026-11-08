package org.domainforge.web.api;

/**
 * 把第三方框架的原生异常转为平台失败类型（{@code BusinessRuleViolation} 或 {@code DependencyUnavailableException}），
 * 使它们同样经过 {@link Problems#dispatch} 这一个分派点。
 *
 * <p>只给平台组件包装第三方框架用，通过 {@code META-INF/services/} 登记；业务异常直接继承 shared 的基类，不需要它。
 */
public interface FailureTranslator {
    /**
     * 转换认识的异常。
     *
     * @param failure 入口捕获的异常
     * @return 平台失败类型；不认识时返回 null
     */
    RuntimeException translate(Exception failure);
}

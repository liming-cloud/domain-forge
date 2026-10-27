package org.domainforge.access.internal;

import org.springframework.core.env.Environment;

/**
 * Same-Token 校验已关闭的标记：只有本地开发与测试可以关闭，部署环境关闭会让后端可被绕过网关直连。
 */
public final class SameTokenDisabled {
    private SameTokenDisabled() {
    }

    /**
     * 部署环境以 config-center profile 识别；该环境下关闭即抛出异常阻止启动。
     *
     * @param environment 当前环境
     * @return 关闭标记
     */
    public static SameTokenDisabled check(Environment environment) {
        if (environment.matchesProfiles("config-center"))
            throw new IllegalStateException("部署环境（config-center）不得关闭 Same-Token 校验");
        return new SameTokenDisabled();
    }
}

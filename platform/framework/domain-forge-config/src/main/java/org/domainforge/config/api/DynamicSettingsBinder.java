package org.domainforge.config.api;

/**
 * 把配置前缀绑定为可动态更新的配置组。服务在 bootstrap 中为每个配置组声明一次。
 */
public interface DynamicSettingsBinder {
    /**
     * 立即绑定并校验；前缀下没有配置、含未知键或取值非法时抛出异常，使启动失败。
     *
     * @param prefix 配置前缀，例如 {@code iam.login-protection}
     * @param type   不可变配置组类型
     */
    <T> DynamicSettings<T> bind(String prefix, Class<T> type);
}

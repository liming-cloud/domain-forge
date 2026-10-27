package org.domainforge.config.api;

/**
 * 一组可不重启更新的配置的当前快照。
 *
 * <p>配置组声明为不可变类型（通常是 record，在构造器中校验取值）。启动时绑定失败阻止启动；运行中配置变化后整组重新绑定，
 * 成功才替换快照，失败保留当前快照并记录。使用方每次需要时调用 {@link #current()}，不要保存返回值供以后使用。
 *
 * @param <T> 配置组类型
 */
public interface DynamicSettings<T> {
    /**
     * 返回当前生效的整组配置；同一次读取内各字段属于同一次绑定。
     */
    T current();
}

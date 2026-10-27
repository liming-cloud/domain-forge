package org.domainforge.config.internal;

import org.domainforge.config.api.DynamicSettings;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 一个配置组的快照：整组绑定成功才原子替换，失败时保留原快照并把异常交给调用方记录。
 *
 * @param <T> 配置组类型
 */
final class BoundSettings<T> implements DynamicSettings<T> {
    private final String prefix;
    private final Class<T> type;
    private final Supplier<Binder> binder;
    private final AtomicReference<T> snapshot = new AtomicReference<>();

    BoundSettings(String prefix, Class<T> type, Supplier<Binder> binder) {
        this.prefix = prefix;
        this.type = type;
        this.binder = binder;
        snapshot.set(bind());
    }

    @Override
    public T current() {
        return snapshot.get();
    }

    String prefix() {
        return prefix;
    }

    /**
     * 判断一次配置变化是否涉及本组；键名按 Spring 的宽松规则比较前缀。
     */
    boolean affectedBy(Iterable<String> keys) {
        String normalized = normalize(prefix);
        for (String key : keys) {
            String candidate = normalize(key);
            if (candidate.equals(normalized) || candidate.startsWith(normalized + ".")) return true;
        }
        return false;
    }

    /**
     * 重新绑定整组；成功时替换快照，失败时抛出异常且快照不变。
     */
    void refresh() {
        snapshot.set(bind());
    }

    private T bind() {
        return binder.get()
                .bind(prefix, Bindable.of(type), new NoUnboundElementsBindHandler(BindHandler.DEFAULT))
                .orElseThrow(() -> new IllegalStateException("配置组缺失: " + prefix));
    }

    private static String normalize(String key) {
        return key.toLowerCase().replace("-", "").replace("_", "");
    }
}

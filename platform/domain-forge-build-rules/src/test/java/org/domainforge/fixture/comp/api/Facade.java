package org.domainforge.fixture.comp.api;

import org.domainforge.fixture.comp.internal.Hidden;

/**
 * 测试组件的公开入口（无状态静态入口类）；组件内部可以使用自己的 internal。
 */
public final class Facade {
    private Facade() {
    }

    public static Object open() {
        return new Hidden();
    }
}

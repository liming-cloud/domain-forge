package org.domainforge.fixture.comp.api;

import org.domainforge.fixture.comp.internal.Hidden;

/**
 * 测试组件的公开接口；组件内部可以使用自己的 internal。
 */
public class Facade {
    Hidden hidden = new Hidden();
}

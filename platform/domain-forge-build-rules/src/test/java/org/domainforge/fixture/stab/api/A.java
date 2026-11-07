package org.domainforge.fixture.stab.api;

import org.domainforge.fixture.stab.internal.B;

/**
 * 反例：较稳定的 api 依赖了依赖很多的 internal。
 */
public class A {
    B b;
}

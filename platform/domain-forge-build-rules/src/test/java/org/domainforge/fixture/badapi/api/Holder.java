package org.domainforge.fixture.badapi.api;

/**
 * 反例：api 包中带实例状态的实现类。
 */
public final class Holder {
    private final String state = "x";

    public String state() {
        return state;
    }
}

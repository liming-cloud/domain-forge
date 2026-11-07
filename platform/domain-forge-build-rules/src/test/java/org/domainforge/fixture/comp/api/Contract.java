package org.domainforge.fixture.comp.api;

/**
 * 测试组件的合同接口、值类型与异常，api 包允许的类型。
 */
public interface Contract {
    record Value(String text) {
    }

    enum Kind {
        A, B
    }

    final class Failure extends RuntimeException {
    }
}

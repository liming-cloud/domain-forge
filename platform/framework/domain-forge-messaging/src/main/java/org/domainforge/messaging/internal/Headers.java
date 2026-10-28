package org.domainforge.messaging.internal;

/**
 * 消息属性名：事件信封的最小字段。
 */
final class Headers {
    static final String EVENT_ID = "df-event-id";
    static final String EVENT_TYPE = "df-event-type";
    static final String EVENT_VERSION = "df-event-version";
    static final String EVENT_KEY = "df-event-key";

    private Headers() {
    }
}

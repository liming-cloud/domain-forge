package fixture.checkstyle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 反例：日志消息没有 event= 前缀、事件名不是 snake_case、用字符串拼接。
 */
public class LogEventFormat {
    private static final Logger LOG = LoggerFactory.getLogger(LogEventFormat.class);

    void logs(String name) {
        LOG.info("cache_fill_failed region={}", name);
        LOG.warn("拒绝缺少有效 Same-Token 的请求：{}", name);
        LOG.error("event=CacheFillFailed region={}", name);
        LOG.debug("event= region={}", name);
        LOG.trace("event=cache_fill_failedregion={}", name);
        LOG.info("event=cache_fill_failed region=" + name);
        LoggerFactory.getLogger("cleanup").warn("cleanup_failed");
    }
}

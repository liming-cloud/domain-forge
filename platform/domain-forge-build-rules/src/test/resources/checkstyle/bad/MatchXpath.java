package fixture.checkstyle;

import java.util.concurrent.TimeUnit;

import static java.lang.Thread.sleep;

/**
 * 反例：测试中的固定时长等待。
 */
public class MatchXpath {
    void waits() throws InterruptedException {
        Thread.sleep(100);
        TimeUnit.MILLISECONDS.sleep(100);
        sleep(100);
    }
}

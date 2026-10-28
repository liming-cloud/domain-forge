package @@PACKAGE@@.application.query.tally;

import @@PACKAGE@@.application.port.tally.NoteTallies;
import org.springframework.stereotype.Service;

/**
 * 当前账号在 @@SOURCE@@ 中创建的便笺数（最终一致：事件到达前不计入）。
 */
@Service
public class NoteTallyQueries {
    private final NoteTallies tallies;

    /**
     * 只依赖统计端口。
     */
    public NoteTallyQueries(NoteTallies tallies) {
        this.tallies = tallies;
    }

    /**
     * 某账号的便笺数。
     */
    public long count(long ownerId) {
        return tallies.count(ownerId);
    }
}

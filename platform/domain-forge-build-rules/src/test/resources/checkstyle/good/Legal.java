package fixture.checkstyle;

import java.util.List;

/**
 * 正例：类型有说明，复杂度与语句数在上限内，字符串只是普通文字。
 */
public class Legal {
    /**
     * 按名称挑选；提到 select 与 from 的普通文字不是 SQL。
     */
    public String pick(List<String> names, boolean upper) {
        String hint = "update the status when names are selected from the list";
        if (names.isEmpty()) {
            return hint;
        }
        String first = names.get(0);
        return upper ? first.toUpperCase() : first;
    }

    /**
     * 名为 sleep 的变量上的调用不是固定时长等待。
     */
    public boolean blank(String sleep) {
        return sleep.isBlank();
    }
}

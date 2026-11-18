package fixture.checkstyle;

/**
 * 反例：一行两条语句。
 */
public class OneStatementPerLine {
    void run() {
        int a = 1; int b = 2;
        System.out.println(a + b);
    }
}

package fixture.checkstyle;

/**
 * 反例：方法体内的说明注释不属于任何声明。
 */
public class InvalidJavadocPosition {
    void run() {
        /** 说明写在了语句前。 */
        System.out.println();
    }
}

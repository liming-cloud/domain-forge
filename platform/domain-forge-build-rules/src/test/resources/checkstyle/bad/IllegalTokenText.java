package fixture.checkstyle;

/**
 * 反例：Java 字符串中的 SQL。
 */
public class IllegalTokenText {
    String count = "SELECT count(*) FROM domainforge_outbox WHERE event_id = ?";
}

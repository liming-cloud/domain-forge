package fixture.checkstyle;

/**
 * 反例：文本块中的 SQL。
 */
public class IllegalTokenText_TextBlock {
    String reset = """
            update domainforge_outbox
               set sent_at = null
             where event_id = ?
            """;
}

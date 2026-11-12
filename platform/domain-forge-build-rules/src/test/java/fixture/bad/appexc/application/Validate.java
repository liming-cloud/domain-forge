package fixture.bad.appexc.application;

public class Validate {
    void check(String input) {
        if (input.isBlank()) throw new IllegalArgumentException("blank");
    }
}

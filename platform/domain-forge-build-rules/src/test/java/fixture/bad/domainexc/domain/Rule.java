package fixture.bad.domainexc.domain;

public class Rule {
    void check(int value) {
        if (value < 0) throw new IllegalArgumentException("negative");
    }
}

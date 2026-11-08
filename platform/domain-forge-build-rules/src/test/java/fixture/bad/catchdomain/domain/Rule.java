package fixture.bad.catchdomain.domain;

public class Rule {
    void check() {
        throw new Rejected();
    }
}

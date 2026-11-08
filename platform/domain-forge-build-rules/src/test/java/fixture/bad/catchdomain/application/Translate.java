package fixture.bad.catchdomain.application;

import fixture.bad.catchdomain.domain.Rejected;
import fixture.bad.catchdomain.domain.Rule;

public class Translate {
    Rule rule;

    void run() {
        try {
            rule.toString();
        } catch (Rejected rejected) {
            throw new IllegalStateException(rejected);
        }
    }
}

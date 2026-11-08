package fixture.bad.repoentity.domain;

import org.jmolecules.ddd.annotation.AggregateRoot;
import org.jmolecules.ddd.annotation.Identity;

@AggregateRoot
public class Order {
    @Identity
    long id;

    Line line;
}

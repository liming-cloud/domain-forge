package fixture.bad.multiwrite.domain;

import org.jmolecules.ddd.annotation.AggregateRoot;
import org.jmolecules.ddd.annotation.Identity;

@AggregateRoot
public class Stock {
    @Identity
    long id;
}

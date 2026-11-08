package fixture.bad.aggref.domain;

import org.jmolecules.ddd.annotation.AggregateRoot;
import org.jmolecules.ddd.annotation.Identity;

@AggregateRoot
public class Customer {
    @Identity
    long id;
}

package fixture.good.sample.domain;

import org.domainforge.shared.BusinessRuleViolation;
import org.jmolecules.ddd.annotation.AggregateRoot;
import org.jmolecules.ddd.annotation.Identity;

import java.util.UUID;

@AggregateRoot
public class Order {
    @Identity
    UUID id = UUID.randomUUID();

    long customerId;

    void require() {
        if (customerId <= 0) throw new BusinessRuleViolation();
    }
}

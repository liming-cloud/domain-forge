package fixture.good.sample.domain;

import org.jmolecules.event.annotation.DomainEvent;

@DomainEvent
public record OrderPlaced(long customerId) {
}

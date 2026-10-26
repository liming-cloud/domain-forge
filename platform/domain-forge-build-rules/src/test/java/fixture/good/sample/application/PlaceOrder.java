package fixture.good.sample.application;

import fixture.good.sample.domain.Order;
import org.domainforge.fixture.comp.api.Facade;
import org.springframework.stereotype.Service;

@Service
public class PlaceOrder {
    Order order;
    Facade facade;
}

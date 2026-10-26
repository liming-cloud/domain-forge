package fixture.good.sample.application;

import fixture.good.sample.domain.Order;
import org.domainforge.fixture.comp.api.Facade;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

@Service
public class PlaceOrder {
    Order order;
    Facade facade;
    TransactionOperations transactions;

    Order place() {
        return transactions.execute(status -> order);
    }
}

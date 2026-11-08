package fixture.good.sample.application;

import fixture.good.sample.domain.Order;
import fixture.good.sample.domain.repository.CustomerRepository;
import fixture.good.sample.domain.repository.OrderRepository;
import org.domainforge.fixture.comp.api.Facade;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

@Service
public class PlaceOrder {
    Order order;
    Facade facade;
    TransactionOperations transactions;
    OrderRepository orders;
    CustomerRepository customers;

    Order place() {
        return transactions.execute(status -> {
            customers.find(1);
            orders.insert(order);
            return order;
        });
    }

    void registerCustomer() {
        customers.insert(null);
    }
}

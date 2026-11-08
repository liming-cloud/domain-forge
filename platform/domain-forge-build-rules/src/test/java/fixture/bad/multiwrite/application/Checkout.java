package fixture.bad.multiwrite.application;

import fixture.bad.multiwrite.domain.repository.OrderRepository;
import fixture.bad.multiwrite.domain.repository.StockRepository;

public class Checkout {
    OrderRepository orders;
    StockRepository stocks;

    void place() {
        save();
    }

    private void save() {
        orders.insert(null);
        stocks.update(null);
    }

    void viaLambda() {
        Runnable task = () -> {
            orders.insert(null);
            stocks.update(null);
        };
        task.run();
    }
}

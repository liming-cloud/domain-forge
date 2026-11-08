package fixture.good.sample.domain.repository;

import fixture.good.sample.domain.Order;

public interface OrderRepository {
    Order find(long id);

    void insert(Order order);
}

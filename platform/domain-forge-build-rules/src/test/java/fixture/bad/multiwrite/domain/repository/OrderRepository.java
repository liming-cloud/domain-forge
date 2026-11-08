package fixture.bad.multiwrite.domain.repository;

import fixture.bad.multiwrite.domain.Order;

public interface OrderRepository {
    void insert(Order order);
}

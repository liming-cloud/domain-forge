package fixture.good.sample.domain.repository;

import fixture.good.sample.domain.Customer;

public interface CustomerRepository {
    Customer find(long id);

    void insert(Customer customer);
}

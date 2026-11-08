package fixture.bad.multiwrite.domain.repository;

import fixture.bad.multiwrite.domain.Stock;

public interface StockRepository {
    void update(Stock stock);
}

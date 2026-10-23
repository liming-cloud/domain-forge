package fixture.good.sample.infrastructure;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import fixture.good.sample.domain.Order;
import java.sql.Connection;

public class OrderStore {
  Order order;
  Connection connection;

  void query(AbstractWrapper wrapper) {
    wrapper.eq("id", 1);
  }
}

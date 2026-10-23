package fixture.bad.rawsql.infrastructure;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;

public class RawWrapper {
  void query(AbstractWrapper wrapper) {
    wrapper.apply("1 = 1");
  }
}

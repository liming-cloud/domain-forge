package fixture.good.sample.bootstrap;

import fixture.good.sample.infrastructure.OrderStore;
import fixture.good.sample.interfaces.OrderController;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;

@MapperScan(basePackages = "fixture.good.sample.infrastructure", annotationClass = Mapper.class)
public class App {
    OrderStore store;
    OrderController controller;
}

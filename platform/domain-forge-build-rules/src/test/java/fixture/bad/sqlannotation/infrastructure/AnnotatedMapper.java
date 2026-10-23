package fixture.bad.sqlannotation.infrastructure;

import org.apache.ibatis.annotations.Select;

public interface AnnotatedMapper {
  @Select("select 1")
  int one();
}

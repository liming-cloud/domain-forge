package @@PACKAGE@@.infrastructure.persistence;

import @@PACKAGE@@.domain.repository.RecordWriter;
import org.springframework.stereotype.Repository;

/** 实现单库仓储写入，不另起事务或改变应用异常。 */
@Repository
public class SqlRecordWriter implements RecordWriter {
  private final RecordMapper mapper;

  /** 注入当前服务的 Mapper。 */
  public SqlRecordWriter(RecordMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public void insert(long id, String value) {
    var row = new RecordPo();
    row.setId(id);
    row.setValue(value);
    mapper.insert(row);
  }
}

package @@PACKAGE@@.infrastructure.query;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import @@PACKAGE@@.application.query.RecordFilter;
import @@PACKAGE@@.application.query.RecordView;
import @@PACKAGE@@.application.query.Records;
import @@PACKAGE@@.infrastructure.persistence.RecordMapper;
import @@PACKAGE@@.infrastructure.persistence.RecordPo;
import java.util.List;
import org.springframework.stereotype.Repository;

/** 纯查询直达一次参数化 SQL，不补查每行，也不借道领域写入路径。 */
@Repository
public class SqlRecords implements Records {
  private final RecordMapper mapper;

  /** 显式连接只属于本服务的 SQL 实现。 */
  public SqlRecords(RecordMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public List<RecordView> find(RecordFilter filter) {
    var query = new QueryWrapper<RecordPo>().eq("value", filter.value());
    if ("asc".equals(filter.order())) {
      query.orderByAsc("id");
    } else {
      query.orderByDesc("id");
    }
    var rows = mapper.selectPage(new Page<RecordPo>(1, filter.limit(), false), query).getRecords();
    return rows.stream().map(row -> new RecordView(row.getId(), row.getValue())).toList();
  }
}

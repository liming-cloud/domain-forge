package @@PACKAGE@@.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** 原生 Mapper，SQL 和 PO 只属于基础设施。 */
@Mapper
public interface RecordMapper extends BaseMapper<RecordPo> {
  /** 只供验证语句等待上限；不生成到产品业务或裸模板。 */
  @Select("SELECT pg_sleep(8)")
  void waitBeyondStatementDeadline();
}

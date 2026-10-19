package probe.adapter;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.annotations.*;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 仅复制到仓库外隔离工程的合成持久化应用，不作为产品模块交付。 */
@SpringBootApplication
@MapperScan("probe.adapter")
@EnableTransactionManagement(rollbackOn = RollbackOn.ALL_EXCEPTIONS)
public class PersistenceAdapterFixture {
  /** 装配实际分页与版本插件，观察器不替应用安装。 */
  @Bean
  public MybatisPlusInterceptor plugins() {
    var plugins = new MybatisPlusInterceptor();
    plugins.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
    var page = new PaginationInnerInterceptor(DbType.POSTGRE_SQL);
    page.setMaxLimit(2L);
    plugins.addInnerInterceptor(page);
    return plugins;
  }

  /** 保存合成枚举的数据库代码，不将存储注解带入领域记录。 */
  public enum StoredState {
    READY("R"),
    HELD("H");
    @EnumValue private final String code;

    /** 绑定显式存储代码，不依赖枚举名称入库。 */
    StoredState(String code) {
      this.code = code;
    }
  }

  /** 明确类型和生成键的合成 PO，字段只属于基础设施夹具。 */
  @TableName("gate_adapter")
  public static class StoredRow {
    @TableId(type = IdType.AUTO)
    public Long id;

    public String tenant;
    public BigDecimal quantity;
    public LocalDate day;
    public LocalDateTime localAt;
    public OffsetDateTime instantAt;
    public String note;
    public StoredState state;
    @Version public Integer revision;
  }

  /** 重建后仍保留行为的技术无关合成值。 */
  public record Rebuilt(Long id, BigDecimal quantity, boolean ready) {
    /** 根据重建的数量和状态判断可用性。 */
    public boolean usable() {
      return ready && quantity.signum() > 0;
    }
  }

  /** 自定义关联分页结果，只在适配器内部使用。 */
  public static class Summary {
    public Long id;
    public Long details;
  }

  /** 真实通用映射和有限自定义查询，不提供产品接口。 */
  public interface RowMapper extends BaseMapper<StoredRow> {
    /** 按参数限定范围，对关联结果按记录分组计数。 */
    @Select(
        "SELECT r.id, count(d.id) AS details FROM gate_adapter r LEFT JOIN gate_detail d ON"
            + " d.owner_id=r.id WHERE r.tenant=#{tenant} GROUP BY r.id ORDER BY r.id")
    Page<Summary> summary(Page<Summary> page, @Param("tenant") String tenant);

    /** 自定义更新自行维护版本条件，不能依赖方法名推断覆盖。 */
    @Update(
        "UPDATE gate_adapter SET quantity=quantity+1, revision=revision+1 WHERE id=#{id} AND"
            + " revision=#{version}")
    int conditional(@Param("id") long id, @Param("version") int version);

    /** 同值更新与未匹配分别保留原生行数。 */
    @Update("UPDATE gate_adapter SET quantity=#{value} WHERE id=#{id}")
    int same(@Param("id") long id, @Param("value") BigDecimal value);

    /** 由数据库维护合成唯一性约束。 */
    @Insert("INSERT INTO gate_unique(code) VALUES (#{code})")
    int unique(@Param("code") String code);
  }

  /** 只在原生 BATCH 会话中执行的合成 Mapper。 */
  public interface BatchMapper {
    /** 延迟唯一约束用来区分刷新与提交失败。 */
    @Insert("INSERT INTO gate_batch(id, code) VALUES (#{id}, #{code})")
    int insert(@Param("id") long id, @Param("code") String code);
  }

  /** 实际数据库操作入口；所有写入由应用自己的代理承担事务。 */
  @Service("adapter")
  public static class Adapter {
    private final RowMapper mapper;
    private final SqlSessionTemplate batch;
    private final CyclicBarrier versionBarrier = new CyclicBarrier(2);
    private final CyclicBarrier uniqueBarrier = new CyclicBarrier(2);

    /** 使用应用自身的会话工厂，不从观察器取得数据库连接。 */
    public Adapter(RowMapper mapper, SqlSessionFactory factory) {
      this.mapper = mapper;
      this.batch = new SqlSessionTemplate(factory, ExecutorType.BATCH);
    }

    /** 保存后重新读取精确类型、空值、枚举与数据库返回键，再重建值。 */
    @Transactional
    public String roundtrip() {
      var row = sample("9999.99");
      mapper.insert(row);
      var restored = mapper.selectById(row.id);
      var model = new Rebuilt(restored.id, restored.quantity, restored.state == StoredState.READY);
      return model.id()
          + "|"
          + model.quantity().toPlainString()
          + "|"
          + restored.day
          + "|"
          + restored.localAt
          + "|"
          + restored.instantAt.toInstant()
          + "|"
          + restored.note
          + "|"
          + model.usable();
    }

    /** 显式观察本合同允许的数据库半入舍入，正负值都须验证。 */
    @Transactional
    public String rounding() {
      var positive = sample("1.235");
      var negative = sample("-1.235");
      mapper.insert(positive);
      mapper.insert(negative);
      return mapper.selectById(positive.id).quantity
          + "|"
          + mapper.selectById(negative.id).quantity;
    }

    /** 让超限原生异常传播出事务，不自行解释为成功。 */
    @Transactional
    public String overflow() {
      var row = sample("10000.00");
      mapper.insert(row);
      return String.valueOf(row.id);
    }

    /** 真实插入后抛出异常，验证序列值不会随事务回滚恢复。 */
    @Transactional
    public String keyRollback() {
      mapper.insert(sample("2.00"));
      throw new IllegalStateException("synthetic key rollback");
    }

    /** 验证失败后生成键存在间隙且返回真实保存的 ID。 */
    @Transactional
    public String afterFailure() {
      var row = sample("2.00");
      mapper.insert(row);
      return String.valueOf(mapper.selectById(row.id).id);
    }

    /** 创建每次操作独有的 PO，不共享可变查询对象。 */
    private StoredRow sample(String quantity) {
      var row = new StoredRow();
      row.tenant = "A";
      row.quantity = new BigDecimal(quantity);
      row.day = LocalDate.of(2024, 2, 29);
      row.localAt = LocalDateTime.parse("2024-02-29T12:34:56.123456");
      row.instantAt = OffsetDateTime.parse("2024-02-29T12:34:56.123456+08:00");
      row.state = StoredState.READY;
      row.revision = 0;
      return row;
    }

    /** 第一页保持稳定排序与正确分组总数。 */
    public String firstPage() {
      return page(1, 2, "A");
    }

    /** 后续页不遗漏最后一个没有关联明细的记录。 */
    public String secondPage() {
      return page(2, 2, "A");
    }

    /** 超出页范围时保留总数并返回空集合。 */
    public String emptyPage() {
      return page(9, 2, "A");
    }

    /** SQL 片段只作为绑定参数，不改变范围条件。 */
    public String boundValue() {
      return page(1, 2, "A' OR 1=1 --");
    }

    /** 超过本合同最大规模时由适配器拒绝。 */
    public String oversized() {
      return page(1, 3, "A");
    }

    /** 负数页长不能借原生关闭分页语义绕过约束。 */
    public String negativeSize() {
      return page(1, -1, "A");
    }

    /** 在基础设施内消化分页对象，仅返回技术无关合成内容。 */
    private String page(long current, long size, String tenant) {
      if (current < 1 || size < 1 || size > 2) throw new IllegalArgumentException("page bounds");
      var page = mapper.summary(new Page<>(current, size), tenant);
      return page.getTotal()
          + "|"
          + page.getRecords().stream()
              .map(row -> row.id + ":" + row.details)
              .collect(java.util.stream.Collectors.joining(","));
    }

    /** 两个调用读取相同旧版本后再竞争，由数据库行数判断结果。 */
    @Transactional
    public String competingVersion() throws Exception {
      var row = mapper.selectById(1L);
      versionBarrier.await(5, TimeUnit.SECONDS);
      row.quantity = row.quantity.add(BigDecimal.ONE);
      int affected = mapper.updateById(row);
      return affected + ":" + row.revision;
    }

    /** 分别观察陈旧版本、同值更新和未匹配目标的实际行数。 */
    @Transactional
    public String conditions() {
      int stale = mapper.conditional(1, 0);
      int same = mapper.same(1, new BigDecimal("101.00"));
      int missing = mapper.same(999, BigDecimal.ZERO);
      return stale + ":" + same + ":" + missing;
    }

    /** 两个真实事务竞争同一唯一键，异常由事务边界传播。 */
    @Transactional
    public String competingUnique() throws Exception {
      uniqueBarrier.await(5, TimeUnit.SECONDS);
      return String.valueOf(mapper.unique("ONE"));
    }

    /** 三条合成记录以两条为一批，在一个事务内提交。 */
    @Transactional
    public String batchCommit() {
      return insertBatch(List.of(1L, 2L, 3L), List.of("A", "B", "C"));
    }

    /** 首批已刷新，后续主键冲突仍须整体回滚。 */
    @Transactional
    public String batchFlushFailure() {
      return insertBatch(List.of(4L, 5L, 1L), List.of("D", "E", "F"));
    }

    /** 延迟唯一约束在提交时失败，刷新成功不能当作业务完成。 */
    @Transactional
    public String batchCommitFailure() {
      return insertBatch(List.of(6L, 7L), List.of("Z", "Z"));
    }

    /** 数量超限在任何原生写入前拒绝。 */
    @Transactional
    public String batchOversized() {
      return insertBatch(List.of(8L, 9L, 10L, 11L), List.of("G", "H", "I", "J"));
    }

    /** 只使用真实刷新结果累计数量，不累计 Mapper 的批处理哨兵值。 */
    private String insertBatch(List<Long> ids, List<String> codes) {
      if (ids.size() > 3) throw new IllegalArgumentException("batch bounds");
      BatchMapper writer = batch.getMapper(BatchMapper.class);
      int completed = 0;
      for (int index = 0; index < ids.size(); index++) {
        writer.insert(ids.get(index), codes.get(index));
        if ((index + 1) % 2 == 0 || index + 1 == ids.size()) {
          for (var result : batch.flushStatements()) {
            for (int count : result.getUpdateCounts()) {
              if (count != 1) throw new IllegalStateException("unconfirmed batch result");
              completed += count;
            }
          }
        }
      }
      return String.valueOf(completed);
    }
  }
}

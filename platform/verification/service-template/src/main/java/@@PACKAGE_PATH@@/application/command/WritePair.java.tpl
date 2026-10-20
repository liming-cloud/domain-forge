package @@PACKAGE@@.application.command;

import @@PACKAGE@@.domain.repository.RecordWriter;
import java.io.IOException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 用真实应用代理证明两次同资源写入的一致性；仅属于验证夹具。 */
@Service
public class WritePair {
  private final RecordWriter records;

  /** 注入业务仓储合同，不把 Mapper 或数据源传入应用。 */
  public WritePair(RecordWriter records) {
    this.records = records;
  }

  /** 成功提交，或在第二次写入后触发 checked／运行时异常。 */
  @Transactional
  public void execute(long first, String failure) throws IOException {
    records.insert(first, "first");
    records.insert(first + 1, "second");
    if ("checked".equals(failure)) {
      throw new IOException("验证 checked 回滚");
    }
    if ("runtime".equals(failure)) {
      throw new IllegalStateException("验证运行时回滚");
    }
  }
}

package @@PACKAGE@@.domain.repository;

/** 仅验证同资源写入的领域仓储合同；不是产品业务模型。 */
public interface RecordWriter {
  /** 保存验证记录；调用方拥有事务。 */
  void insert(long id, String value);
}

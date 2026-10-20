package @@PACKAGE@@.application.query;

/** 固定排序与有限分页；查询字符串作为绑定值。 */
public record RecordFilter(String value, int limit, String order) {
  /** 拒绝越界参数；排序只能选择两个明确选项。 */
  public RecordFilter {
    if (value == null
        || value.length() > 64
        || limit < 1
        || limit > 20
        || !("asc".equals(order) || "desc".equals(order))) {
      throw new IllegalArgumentException("查询参数超出已声明边界");
    }
  }
}

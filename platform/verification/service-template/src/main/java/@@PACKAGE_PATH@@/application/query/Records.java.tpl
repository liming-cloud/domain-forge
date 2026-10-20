package @@PACKAGE@@.application.query;

import java.util.List;

/** 只读查询合同；没有编排责任便不增加空转 Handler。 */
public interface Records {
  /** 返回最多二十条视图，查询不写业务表。 */
  List<RecordView> find(RecordFilter filter);
}

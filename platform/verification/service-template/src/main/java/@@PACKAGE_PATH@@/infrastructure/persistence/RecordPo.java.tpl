package @@PACKAGE@@.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 测试专用关系映射，不向应用或领域泄漏。 */
@TableName("probe_entry")
public class RecordPo {
  @TableId(type = IdType.INPUT)
  private Long id;

  private String value;

  /** 返回数据库主键。 */
  public Long getId() {
    return id;
  }

  /** 供 Mapper 设置主键。 */
  public void setId(Long id) {
    this.id = id;
  }

  /** 返回数据库值。 */
  public String getValue() {
    return value;
  }

  /** 供 Mapper 设置值。 */
  public void setValue(String value) {
    this.value = value;
  }
}

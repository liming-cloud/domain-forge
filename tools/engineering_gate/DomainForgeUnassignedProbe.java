package org.domainforge.gate;

/** 隔离校准的临时未声明入口；不提供产品接口或任何鉴权实现。 */
public final class DomainForgeUnassignedProbe {
  /**
   * 只有实际应用缺少默认拒绝时才会被调用，不访问业务数据。
   *
   * @return 用于识别被错误放行的固定标识
   */
  @org.springframework.web.bind.annotation.ResponseBody
  public String unassigned() {
    return "UNASSIGNED_PROBE_EXECUTED";
  }
}

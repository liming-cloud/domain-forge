package org.domainforge.access.api;

import org.domainforge.shared.CurrentSubject;

/**
 * 当前令牌的完整可信事实源；IAM 读取本地数据库，其他服务在自身基础设施中调用 IAM。
 */
@FunctionalInterface
public interface CurrentSubjectSource {
    /**
     * 校验当前令牌及本应用要求的安全事实；无身份、拒绝和依赖故障使用公共失败分类。
     */
    CurrentSubject resolve(String token);
}

# 活动任务与证据索引

<!-- control-plane:generated; edit catalog/task facts, not this view -->


## 活动与待处理

- [reusable-web-component](reusable-web-component.md#目标与输入)：收尾待处理；记录提交自身 SHA、远端和 CI 尚须在推送后取得并核验，不能自引用或预写通过；实际收尾对象和结果原位维护于 .run-artifacts/f01-close/delivery-final.json。
- reusable-web-component 待处理：验收记录提交自身的远端、CI 及 delivery 必须在提交推送后核对；实际结果原位维护于 .run-artifacts/f01-close/delivery-final.json，不预记通过或追加 CI 进度提交
- reusable-web-component 待处理：原生漏洞告警继续保留，具体可利用性和生产风险处置未完成；人工验收不构成风险豁免或生产安全放行
- reusable-web-component 待处理：完成必要收尾后，仅提交阶段 1b 的迁移清单、影响范围和验证要求供确认；当前不实施迁移、不修改 CI 或扩展业务功能

## 归档查询

[按需查询归档任务与历史证据](archive.md)；默认不加载。

# Domain Forge

公司级 DDD 微服务脚手架，交付可复用基础组件、登录注册与身份权限等基础后端、业务服务起步模板及部署参考方案，并指导 AI 编码与人工审核。产品责任与当前完成状态以文档正文为准。

- [产品定义与首版范围](docs/product/product-definition.md#产品目的)
- [当前恢复点与确认记录](docs/product/rebuild.md#当前恢复点)
- [整体研发结构](docs/design/development-structure.md)
- [当前 R2 组件实现任务](docs/tasks/reusable-web-component.md#目标与输入)
- [文档总索引](docs/index.md) · [Agent 入口](AGENTS.md)

## 开发与验证入口

当前包含最小 Boot 技术探针、`web-support` 组件、平台 BOM 和两个独立消费验证工程。固定环境见[工程使用说明](docs/design/minimal-engineering.md#使用路径)，组件获取与接入见[R2 使用路径](docs/design/reusable-web-component.md#两个独立消费项目与使用路径)。

```bash
# 仓库根执行；先按工程说明选择固定 JDK 和工具环境。
code/mvnw -f code/pom.xml -B -ntp verify
python3 -B tools/control_plane/control_plane.py check
python3 -B tools/product_gate/verify.py --project .
```

`code/` 管理工程源码及可执行资源，`docs/` 管理文档正文，`tools/` 管理质量工具。普通 Maven 构建不替代完整质量验证；当前阶段 1a 的 F01 本地修复、后续候选核验及待人工验收状态见[任务记录](docs/tasks/reusable-web-component.md#唯一任务记录)，不表示整个 R2 或业务模板已经交付。

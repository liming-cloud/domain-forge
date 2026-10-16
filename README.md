# Domain Forge

为多个业务项目提供版本化组件、服务和轻量起步模板。产品责任与当前完成状态以文档正文为准。

- [产品定义与首版范围](docs/product/product-definition.md#产品目的)
- [当前恢复点与确认记录](docs/product/rebuild.md#当前恢复点)
- [整体研发结构](docs/design/development-structure.md)
- [当前任务及 R1 迁移清单](docs/tasks/minimal-engineering.md#r1-迁移清单与验证范围)
- [文档总索引](docs/index.md) · [Agent 入口](AGENTS.md)

## 开发与验证入口

当前代码只有最小 Boot 技术探针，位于 `code/verification/boot-smoke/`。固定环境与完整使用步骤见[工程使用说明](docs/design/minimal-engineering.md#使用路径)。

```bash
# 仓库根执行；先按工程说明选择固定 JDK 和工具环境。
code/mvnw -f code/pom.xml -B -ntp verify
python3 -B tools/control_plane/control_plane.py check
python3 -B tools/product_gate/verify.py --project .
```

`code/` 管理工程源码及可执行资源，`docs/` 管理文档正文，`tools/` 管理质量工具。普通 Maven 构建不替代完整质量验证；当前探针不表示平台能力或业务模板已经交付。

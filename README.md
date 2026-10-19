# Domain Forge

公司级 DDD 微服务脚手架，交付可复用基础组件、登录注册与身份权限等基础后端、业务服务起步模板及部署参考方案，并指导 AI 编码与人工审核。产品责任与当前完成状态以文档正文为准。

- [产品定义与首版范围](docs/product/01-产品定义.md#产品目的)
- [当前恢复点与确认记录](docs/product/02-研发路线.md#当前恢复点)
- [整体研发结构](docs/architecture/01-总体架构.md)
- [当前产品迁移任务](docs/work/02-产品迁移.md#目标与输入)
- [文档总索引](docs/00-文档导航.md) · [Agent 入口](AGENTS.md)

## 开发与验证入口

当前包含最小 Boot 技术探针、`web-support` 组件、平台 BOM 和两个独立消费验证工程。固定环境见[工程使用说明](docs/architecture/02-工程基线.md#使用路径)，组件获取与接入见[R2 使用路径](docs/architecture/03-接口响应组件.md#两个独立消费项目与使用路径)。

```bash
# 仓库根执行；先按工程说明选择固定 JDK 和工具环境。
platform/mvnw -f platform/pom.xml -B -ntp verify
python3 -B tools/control_plane/control_plane.py check
python3 -B tools/product_gate/verify.py --project .
```

`platform/` 管理实际产品工程，`docs/` 管理当前结论文档，`tools/` 管理质量工具；目标目录及后续顺序见总体架构。普通 Maven 构建不替代完整质量验证。当前授权、验证结果与剩余风险见[产品迁移记录](docs/work/02-产品迁移.md#唯一任务记录)。

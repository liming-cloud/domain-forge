# 组件默认配置

`domain-forge-defaults` 统一加载各组件的默认配置。服务不必显式依赖它，带默认配置的组件（access-control、cache、config、service-call、tracing）已依赖它。规则卡见[配置与运行 O-01、O-03](../../rules/08-配置与运行.md)。

## 合同

| 项 | 行为 |
|---|---|
| 位置 | 组件在 `src/main/resources/META-INF/domain-forge/<组件>-defaults.yml` 放默认值，并依赖 `domain-forge-defaults`；不再各写 `EnvironmentPostProcessor` |
| 优先级 | 并入 Spring Boot 的 `defaultProperties`（应用配置加载后总在末尾）：服务 YAML、profile、环境变量、启动参数、Nacos 配置都可覆盖 |
| 时机 | 在应用配置加载之前执行，配置组件的默认值（如 Nacos 导入检查开关）在导入检查前已可见 |
| 已有默认值 | 应用经 `SpringApplication.setDefaultProperties` 设的同名项保留原值 |
| 重复声明 | 两个文件声明同一项时启动失败（`组件默认配置重复声明 <项>：<文件>、<文件>`）；每项配置只有一个归属组件（O-01） |

定时任务组件的 worker 配置需按开关与应用类型计算，仍由 `SchedulingDefaults` 生成，不经本组件。

## 验证

`ComponentDefaultsTest`：真实启动流程中服务配置与启动参数覆盖默认值、未覆盖项保留、`setDefaultProperties` 不被改写；重复声明时失败。各组件的 `AccessDefaultsTest`、`CacheDefaultsTest` 验证本组件 YAML 的内容。

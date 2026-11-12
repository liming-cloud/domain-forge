# 0026 升级 Java 25 并启用 AOT 缓存

- 状态：已接受（2026-11-12）
- 关联 issue：#157（基线 #156，并入 #167）

## 背景

[启动基线](../platform/性能基线.md#启动)显示服务启动受 CPU 限制：1 核下 IAM `Started` 9.9 秒、order-service 10.5 秒，大头是 JVM 加载、链接约 2.2 万个类与 Spring 装配，没有单个外部依赖的等待超过 0.5 秒。Spring Boot 推荐在 Java 25 及以上用 JVM AOT 缓存（JEP 483/514）取代 CDS（[AOT Cache](https://docs.spring.io/spring-boot/reference/packaging/aot-cache.html)、[Efficient Deployments](https://docs.spring.io/spring-boot/reference/packaging/efficient.html)），它预先加载、链接类并记录方法画像，正对这部分开销。

## 决定

- **JDK 25**：编译目标 `release 25`，CI 与运行镜像（Temurin 25 JRE，按摘要固定）同步。依赖与插件不升级：在 25 上完整 `mvn verify` 与部署验证通过。
- **解包运行**：镜像构建时用 `jarmode=tools extract` 解包（AOT 缓存不能缓存嵌套 jar 中的类）；三个服务 pom 去掉 `includeTools=false`。
- **镜像内自训练**：构建时按 jar、依赖与 JDK 算出指纹写入镜像；容器启动时在服务的命名卷 `<服务名>-aot` 中找该指纹的缓存，没有就先做一次训练运行（`-XX:AOTCacheOutput`、`-Dspring.context.exit=onRefresh`）再以 `-XX:AOTCache` 启动。训练刷新完整上下文（含 Flyway），在启动生命周期组件之前退出，只跑到上下文启动、不带流量。训练失败时不带缓存照常启动。卷中保留最近两份缓存。
- **日志仍逐行为 JSON**：训练输出写入卷中 `training.log`，只把其中的 JSON 行转到容器日志；JDK 生成缓存时的提示与组装子进程的输出不进入容器日志。
- **显式放行受限接口**：启动参数加 `--enable-native-access=ALL-UNNAMED`（netty 加载本地库）与 `--sun-misc-unsafe-memory-access=allow`（nacos-client 3.1.1、rocketmq-client-java 5.2.2 内置的 guava 与 netty，PowerJob 带入的 fastjson 2.0.58），否则 JDK 25 每次启动在标准错误打出非 JSON 警告，使日志检查失败。这些库不再使用 `sun.misc.Unsafe` 与受限方法后去掉对应参数。
- **镜像安装 curl**：Temurin 25 镜像不再自带 curl、wget，健康检查与验证脚本要在容器内用 curl。

不采用：

- 部署阶段单独的一次性 compose 训练服务（issue 初定）：compose 每次 `up` 都会重跑已退出的一次性服务，仍要“已存在就跳过”的脚本；且训练在旧版本仍在运行时就执行 Flyway，改变了迁移与旧版本停机的先后关系。
- 在 `docker build` 中训练：训练要连数据库、Redis、Nacos。
- 在 Java 21 上用 CDS；全局懒加载（违反 O-05）；GraalVM 原生镜像。

## 后果

- 1 核 `Started`：IAM 9.9 → 3.5 秒，order-service 10.5 → 4.9 秒；容器启动到就绪 11.5 → 4.8 秒、12.3 → 6.3 秒；首个请求不高于基线，达到 #157 的目标（[性能基线](../platform/性能基线.md#结果java-25aot-缓存2026-11-12)）。启动期 GC 降到约 20 次、0.4 秒，运行镜像不显式设置初始堆或 GC（原 #167）。
- 新镜像首次启动多一次上下文刷新（训练），之后的重启直接用缓存。迁移仍在旧容器停止后、由新镜像首次启动执行，升级与回退流程不变；应用回退到上一个镜像时命中卷中保留的旧缓存。
- 训练运行中定时任务 worker 会短暂连上 PowerJob（它在 Bean 初始化时启动），不在 Nacos 注册、不执行 `ApplicationRunner`。
- 缓存文件被替换或损坏时 JVM 忽略它照常启动（较慢），容器日志出现 `[error][aot]` 开头的非 JSON 行，作为异常信号保留；删除该文件后重启即重新训练。
- 用法见[部署参考](../../deploy/README.md#启动与-aot-缓存)。

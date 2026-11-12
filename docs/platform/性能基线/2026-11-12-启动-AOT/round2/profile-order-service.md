本次（带 JFR）：容器启动到就绪 12.19 秒，Started 9.986 秒。

### 日志时间线：main 线程最长的 10 段

相邻两条 main 线程日志之间的墙钟间隔，即“上一条之后、下一条之前”在做的事。

| 秒 | 上一条 | 下一条 |
|---|---|---|
| 3.08 | 容器启动 | Starting OrderServiceApplication v0.1.0 using Java 25.0.5 with PID 1 (/app/app.j |
| 1.38 | [Nacos Config] Load config[dataId=order-service.yaml, group=DEFAULT_GROUP] succe | Multiple Spring Data modules found, entering strict repository configuration mod |
| 1.37 | Redisson 4.8.0 | For 'iam' URL not provided. Will try picking an instance via load-balancing. |
| 1.03 | Register ApplicationContext instances org.springframework.boot.web.server.servle | [PowerJobWorker] start to initialize PowerJobWorker... |
| 0.73 | BeanFactory id=8fd77353-f62f-3d45-9f50-2dda2896d786 | Tomcat initialized with port 8090 (http) |
| 0.57 | Schema "order_service" is up to date. No migration necessary. | Register ApplicationContext instances org.springframework.boot.web.server.servle |
| 0.52 | For 'iam' URL not provided. Will try picking an instance via load-balancing. | HikariPool-1 - Starting... |
| 0.40 | [Nacos Config] Listening config: dataId=order-service.yaml, group=DEFAULT_GROUP | 就绪检查通过 |
| 0.39 | Finished Spring Data repository scanning in 6 ms. Found 0 Redis repository inter | BeanFactory id=8fd77353-f62f-3d45-9f50-2dda2896d786 |
| 0.38 | Started OrderServiceApplication in 9.986 seconds (process running for 11.175) | [PowerJobClient] refresh auth info successfully! |

### JFR：CPU 样本按组件（全部 Java 线程，到就绪为止）

样本 775 个；GC 21 次，停顿合计 0.44 秒；已加载类 23488 个。占比只表示 Java 代码中的相对分布，不含 JIT 编译、GC 与 JVM 内部的类链接。

| 组件 | 占比 | 其中在类加载与读 jar |
|---|---|---|
| Spring（扫描、解析、代理、Bean 装配） | 51% | 16% |
| Nacos | 11% | 2% |
| 平台与服务代码 | 11% | 1% |
| Tomcat | 10% | 3% |
| Netty | 3% | 0% |
| Logback | 2% | 0% |
| PowerJob | 2% | 1% |
| MyBatis | 2% | 0% |
| org.apache.commons.logging | 1% | 1% |
| JDK | 1% | 0% |

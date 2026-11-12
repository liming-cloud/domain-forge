本次（带 JFR）：容器启动到就绪 13.44 秒，Started 11.011 秒。

### 日志时间线：main 线程最长的 10 段

相邻两条 main 线程日志之间的墙钟间隔，即“上一条之后、下一条之前”在做的事。

| 秒 | 上一条 | 下一条 |
|---|---|---|
| 3.50 | 容器启动 | Starting OrderServiceApplication v0.1.0 using Java 21.0.12.1 with PID 1 (/app/ap |
| 1.63 | Redisson 4.8.0 | For 'iam' URL not provided. Will try picking an instance via load-balancing. |
| 1.41 | [Nacos Config] Load config[dataId=order-service.yaml, group=DEFAULT_GROUP] succe | Multiple Spring Data modules found, entering strict repository configuration mod |
| 1.01 | Register ApplicationContext instances org.springframework.boot.web.server.servle | [PowerJobWorker] start to initialize PowerJobWorker... |
| 0.93 | BeanFactory id=8fd77353-f62f-3d45-9f50-2dda2896d786 | Tomcat initialized with port 8090 (http) |
| 0.60 | For 'iam' URL not provided. Will try picking an instance via load-balancing. | HikariPool-1 - Starting... |
| 0.56 | Finished Spring Data repository scanning in 6 ms. Found 0 Redis repository inter | BeanFactory id=8fd77353-f62f-3d45-9f50-2dda2896d786 |
| 0.49 | Schema "order_service" is up to date. No migration necessary. | Register ApplicationContext instances org.springframework.boot.web.server.servle |
| 0.47 | [Nacos Config] Listening config: dataId=order-service.yaml, group=DEFAULT_GROUP | 就绪检查通过 |
| 0.40 | Started OrderServiceApplication in 11.011 seconds (process running for 12.346) | [PowerJobClient] refresh auth info successfully! |

### JFR：CPU 样本按组件（全部 Java 线程，到就绪为止）

样本 697 个；GC 83 次，停顿合计 0.58 秒；已加载类 22713 个。占比只表示 Java 代码中的相对分布，不含 JIT 编译、GC 与 JVM 内部的类链接。

| 组件 | 占比 | 其中在类加载与读 jar |
|---|---|---|
| Spring（扫描、解析、代理、Bean 装配） | 48% | 43% |
| Tomcat | 16% | 14% |
| Nacos | 12% | 10% |
| 平台与服务代码 | 9% | 7% |
| PowerJob | 5% | 5% |
| MyBatis | 2% | 2% |
| Netty | 2% | 1% |
| RocketMQ | 2% | 2% |
| Redisson | 1% | 1% |
| Hikari／JDBC | 1% | 0% |

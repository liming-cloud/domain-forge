本次（带 JFR）：容器启动到就绪 13.2 秒，Started 10.895 秒。

### 日志时间线：main 线程最长的 10 段

相邻两条 main 线程日志之间的墙钟间隔，即“上一条之后、下一条之前”在做的事。

| 秒 | 上一条 | 下一条 |
|---|---|---|
| 3.50 | 容器启动 | Starting OrderServiceApplication v0.1.0 using Java 21.0.12.1 with PID 1 (/app/ap |
| 1.62 | Redisson 4.8.0 | For 'iam' URL not provided. Will try picking an instance via load-balancing. |
| 1.39 | [Nacos Config] Load config[dataId=order-service.yaml, group=DEFAULT_GROUP] succe | Multiple Spring Data modules found, entering strict repository configuration mod |
| 1.08 | Register ApplicationContext instances org.springframework.boot.web.server.servle | [PowerJobWorker] start to initialize PowerJobWorker... |
| 0.92 | BeanFactory id=8fd77353-f62f-3d45-9f50-2dda2896d786 | Tomcat initialized with port 8090 (http) |
| 0.61 | For 'iam' URL not provided. Will try picking an instance via load-balancing. | HikariPool-1 - Starting... |
| 0.49 | Schema "order_service" is up to date. No migration necessary. | Register ApplicationContext instances org.springframework.boot.web.server.servle |
| 0.48 | Finished Spring Data repository scanning in 6 ms. Found 0 Redis repository inter | BeanFactory id=8fd77353-f62f-3d45-9f50-2dda2896d786 |
| 0.42 | Started OrderServiceApplication in 10.895 seconds (process running for 12.188) | [PowerJobClient] refresh auth info successfully! |
| 0.39 | [WorkerNetUtils] initialize PingPongSocketServer successfully~ | [WorkerNetUtils] [networkInterface:name:eth0 (eth0),inetAddress:/172.31.250.133] |

### JFR：CPU 样本按组件（全部 Java 线程，到就绪为止）

样本 616 个；GC 82 次，停顿合计 0.63 秒；已加载类 23111 个。占比只表示 Java 代码中的相对分布，不含 JIT 编译、GC 与 JVM 内部的类链接。

| 组件 | 占比 | 其中在类加载与读 jar |
|---|---|---|
| Spring（扫描、解析、代理、Bean 装配） | 45% | 38% |
| Tomcat | 14% | 13% |
| Nacos | 11% | 10% |
| 平台与服务代码 | 10% | 8% |
| PowerJob | 5% | 4% |
| Netty | 4% | 2% |
| MyBatis | 3% | 2% |
| RocketMQ | 2% | 2% |
| Logback | 1% | 1% |
| Redisson | 1% | 1% |

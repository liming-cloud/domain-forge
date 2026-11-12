本次（带 JFR）：容器启动到就绪 12.39 秒，Started 10.215 秒。

### 日志时间线：main 线程最长的 10 段

相邻两条 main 线程日志之间的墙钟间隔，即“上一条之后、下一条之前”在做的事。

| 秒 | 上一条 | 下一条 |
|---|---|---|
| 3.24 | 容器启动 | Starting IamApplication v0.1.0 using Java 21.0.12.1 with PID 1 (/app/app.jar sta |
| 1.60 | Redisson 4.8.0 | Register ApplicationContext instances org.springframework.boot.web.server.servle |
| 1.32 | Root WebApplicationContext: initialization completed in 2787 ms | HikariPool-1 - Starting... |
| 1.31 | [Nacos Config] Load config[dataId=iam.yaml, group=DEFAULT_GROUP] success | Multiple Spring Data modules found, entering strict repository configuration mod |
| 0.92 | BeanFactory id=1d054154-7f0a-33cf-9815-1709f131243e | Tomcat initialized with port 8080 (http) |
| 0.52 | Schema "public" is up to date. No migration necessary. | Redisson 4.8.0 |
| 0.48 | Started IamApplication in 10.215 seconds (process running for 11.477) | [PowerJobClient] refresh auth info successfully! |
| 0.40 | Register ApplicationContext instances org.springframework.boot.web.server.servle | [PowerJobWorker] start to initialize PowerJobWorker... |
| 0.40 | Finished Spring Data repository scanning in 56 ms. Found 0 Redis repository inte | BeanFactory id=1d054154-7f0a-33cf-9815-1709f131243e |
| 0.32 | [WorkerNetUtils] initialize PingPongSocketServer successfully~ | [WorkerNetUtils] [networkInterface:name:eth0 (eth0),inetAddress:/172.31.250.134] |

### JFR：CPU 样本按组件（全部 Java 线程，到就绪为止）

样本 507 个；GC 69 次，停顿合计 0.71 秒；已加载类 22139 个。占比只表示 Java 代码中的相对分布，不含 JIT 编译、GC 与 JVM 内部的类链接。

| 组件 | 占比 | 其中在类加载与读 jar |
|---|---|---|
| Spring（扫描、解析、代理、Bean 装配） | 51% | 44% |
| Nacos | 13% | 11% |
| 平台与服务代码 | 8% | 3% |
| Tomcat | 8% | 7% |
| PowerJob | 6% | 5% |
| Redisson | 5% | 5% |
| Netty | 3% | 2% |
| MyBatis | 2% | 1% |
| JDK | 1% | 0% |
| Logback | 1% | 1% |

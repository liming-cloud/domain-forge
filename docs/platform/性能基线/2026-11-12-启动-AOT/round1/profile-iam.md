本次（带 JFR）：容器启动到就绪 10.14 秒，Started 8.169 秒。

### 日志时间线：main 线程最长的 10 段

相邻两条 main 线程日志之间的墙钟间隔，即“上一条之后、下一条之前”在做的事。

| 秒 | 上一条 | 下一条 |
|---|---|---|
| 2.86 | 容器启动 | Starting IamApplication v0.1.0 using Java 25.0.5 with PID 1 (/app/app.jar starte |
| 1.23 | Redisson 4.8.0 | Register ApplicationContext instances org.springframework.boot.web.server.servle |
| 1.10 | [Nacos Config] Load config[dataId=iam.yaml, group=DEFAULT_GROUP] success | Multiple Spring Data modules found, entering strict repository configuration mod |
| 1.01 | Root WebApplicationContext: initialization completed in 2101 ms | HikariPool-1 - Starting... |
| 0.61 | BeanFactory id=1d054154-7f0a-33cf-9815-1709f131243e | Tomcat initialized with port 8080 (http) |
| 0.46 | Schema "public" is up to date. No migration necessary. | Redisson 4.8.0 |
| 0.41 | Started IamApplication in 8.169 seconds (process running for 9.383) | [PowerJobClient] refresh auth info successfully! |
| 0.38 | Register ApplicationContext instances org.springframework.boot.web.server.servle | [PowerJobWorker] start to initialize PowerJobWorker... |
| 0.29 | Finished Spring Data repository scanning in 6 ms. Found 0 Redis repository inter | BeanFactory id=1d054154-7f0a-33cf-9815-1709f131243e |
| 0.20 | [WorkerNetUtils] initialize PingPongSocketServer successfully~ | [WorkerNetUtils] [networkInterface:name:eth0 (eth0),inetAddress:/172.31.250.134] |

### JFR：CPU 样本按组件（全部 Java 线程，到就绪为止）

样本 597 个；GC 23 次，停顿合计 0.41 秒；已加载类 22816 个。占比只表示 Java 代码中的相对分布，不含 JIT 编译、GC 与 JVM 内部的类链接。

| 组件 | 占比 | 其中在类加载与读 jar |
|---|---|---|
| Spring（扫描、解析、代理、Bean 装配） | 51% | 13% |
| Nacos | 11% | 2% |
| 平台与服务代码 | 11% | 2% |
| Tomcat | 6% | 1% |
| MyBatis | 4% | 1% |
| Redisson | 3% | 2% |
| Netty | 3% | 1% |
| PowerJob | 3% | 1% |
| JDK | 2% | 1% |
| Logback | 2% | 0% |

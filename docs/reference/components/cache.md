# 缓存组件（domain-forge-cache）

`domain-forge-cache` 提供 `CacheOperations`、`RedisScriptOperations`、`LockOperations` 三类小接口，底层复用 Redisson 社区版。事务与一致性规则见[事务与一致性](../../rules/02-系统设计.md#事务与一致性)，产品范围见产品定义（平台仓库 `docs/platform/产品定义.md`）。

## 使用合同

access-control 直接依赖 cache，官方 SaTokenDaoForRedisson 与普通缓存共用同一个客户端；安全状态不走 L1、空值缓存或降级，禁止并存第二套 Lettuce／DAO。业务调用组件公开接口，Lua 与 SDK 仅在基础设施职责使用。

cache 的 META-INF/domain-forge/cache-defaults.yml 管唯一 Redis 连接及客户端资源，access-defaults.yml 只管 sa-token。由 [defaults](defaults.md) 组件并入最低优先级的 `defaultProperties`；服务 YAML、profile、环境、启动参数可覆盖，组件不使用 application.yml，不再维护第二套默认值或配置框架。

- 基础缓存使用类型固定的 region＋完整业务键，RBucket 保存有限 TTL。get／put 不自动防护外部回源，getOrLoad 才提供合并和负载保护。
- L1 默认关；开启使用 Caffeine 3.2.4 管理有限容量与 TTL，通过同一 RedissonClient 接收 Redis 原生 keyevent；不建自定义同步总线或按键监听器。启用前核对 E$gx 通知（淘汰模式还需 e）；无配置或无权核对明确拒绝。重订阅清空 L1，按固定 region 集合创建并关闭。L2 TTL 从写入计时，L1 从本地填充计时；失效通知不等于强一致，晚回填／断连可能陈旧。严格当前事实绕过两层，不能以 TTL 承诺距 SQL 提交固定时间内必定最新。Redisson 4.8.0 原生 RClientSideCaching 不满足容量／TTL 要求，因此 L1 采用 Caffeine＋原生通知。
- 穿透：入口参数校验；明确 NotFound 可选短空值缓存（默认关），异常／超时／未授权不能变空值。创建提交后失效空值。随机键仍需回源上限，布隆过滤器不作默认前置。
- 雪崩：普通 TTL 默认在上限内向下 10% 抖动，可关闭；每实例／region 有限回源并发与等待，无无界队列。Redis 失败默认报错，只有明确可丢弃缓存可开启受限回源。
- 击穿：同进程／region／键／固定类型合并查询加载，结束或异常移除记录，键集合受并发上限约束；可选跨实例 RLock，获锁后再读 L2。合并等待者最多等 leader 的许可等待、锁等待（开启时）各一个 load-wait，再加回源的一个 load-wait。无副作用 loader，不承诺丢锁后全局只运行一次，不建新线程池。
- Lua 为已评审 classpath 资源，固定 KEYS／ARGV／模式／返回类型，不接受外部脚本。脚本原子执行不等于异常回滚，超时不等于取消，不盲目重试。不得通过脚本绕过 L1 或操作 Sa-Token 私有键；命名空间不是脚本沙箱。
- 锁为原生 RLock，有限获取等待、finally 由持有线程释放；默认 watchdog，显式 lease 不假称自动续期。不能替代 SQL 约束、行锁或分布式事务。
- 写事务中不得 put／getOrLoad 填入未提交数据；写事务里的 getOrLoad 直接回源（仍受并发上限），不参与合并，读到的值不交给其他线程；evict 有事务时经原生 afterCommit 延后。回调失败只记录实际缓存失效失败，不能把已提交 SQL 伪报回滚／触发重试。旧读晚填与旧空值回填仍可能出现，按普通查询陈旧合同处理，不新增双删队列、栅栏协议或缓存 Outbox。

## 接入

依赖 `domain-forge-cache`，在服务 YAML 中声明区域，注入 `CacheOperations`、`LockOperations`、`RedisScriptOperations`（`org.domainforge.cache.api`）。

```yaml
domainforge:
  cache:
    regions:
      product:                      # region 名
        type: com.company.catalog.application.query.ProductView   # 值类型，JSON 保存
        ttl: 10m                    # 必填，1 秒至 7 天，默认向下 10% 抖动（ttl-jitter）
        null-ttl: 0s                # 明确不存在的短期缓存，默认关闭，至多 5 分钟
        local-capacity: 0           # L1 容量，默认关闭；开启需 Redis E$gx 通知
        local-ttl: 30s
        load-concurrency: 16        # 每实例本区域同时回源上限
        load-wait: 1s               # 等待回源许可、跨实例锁各自的上限；合并等待者按 leader 的预算等
        cross-instance-lock: false
        fallback-on-failure: false  # Redis 故障时 getOrLoad 是否直接回源，仅限可丢弃缓存
```

`CacheRegion` 提供 `get`、`put`、`evict`、`getOrLoad`；Redis 故障、回源并发已满或等待超时抛出 `CacheUnavailableException`。锁等待上限 30 秒，等不到抛出 `LockUnavailableException`。脚本以 `RedisScript(location, resultType, keyPrefix)` 声明，返回 Long、String 或 Boolean，键须在服务自己的前缀内（`satoken:`、`domainforge:` 保留）。

# 短信与验证码

服务发送短信、使用短信验证码统一经 `domain-forge-notification` 组件：`SmsSender` 同步调用短信服务商，`VerificationCodes` 提供验证码的生成、发送、校验、过期与使用限制。验证码用于什么、校验通过后做什么由业务决定。产品要求见通知渠道范围（平台仓库 `docs/platform/产品定义.md`）与短信与验证码功能边界（平台仓库 `docs/platform/产品定义.md`），规则卡见[安全 A-16](../../rules/07-安全与访问控制.md#a-16-验证码只经通知组件)。

决定记录：[ADR 0018](../../decisions/0018-短信与验证码的取舍.md)、[ADR 0019](../../decisions/0019-短信真实发送移出首版.md)。

## 合同

| 操作 | 合同 |
|---|---|
| `SmsSender.send(message)` | 号码规范化（中国大陆 11 位，或带国际区号）后按逻辑模板名找到服务商模板编号并发送；返回服务商回执（受理不等于送达） |
| `VerificationCodes.issue(purpose, phone)` | 先在 Redis 中原子签发（检查重发间隔与当天上限、计数、以新验证码覆盖旧的），再发送短信；短信没有发出时撤销本次签发，不占用重发间隔与当天次数。只返回过期与可重发时间，不返回验证码 |
| `VerificationCodes.verify(purpose, phone, code)` | `VERIFIED`（随即失效）、`MISMATCH`、`EXPIRED`（不存在、过期或已使用）、`LOCKED`（错误次数达到上限，含本次；需要重新获取）。用途之间互不通用 |
| 不在合同内 | 手机号登录、短信注册、通知中心（产品范围外）；送达回执查询；邮件；人机验证（业务在签发前自行决定是否需要） |

失败映射（组件自带）见[错误码](../错误码.md#组件带来的业务拒绝代码)。

校验结果不是异常：业务按自己的合同把 `MISMATCH`／`EXPIRED`／`LOCKED` 映射为业务拒绝。

## 安全

- 验证码由 `SecureRandom` 生成，以 HMAC-SHA256（密钥 `domainforge.verification.key`，Base64，至少 32 字节，只来自环境变量）对“用途＋号码＋验证码”取摘要后保存；号码在 Redis 键中也只以摘要出现。拿到 Redis 数据也不能离线穷举出验证码。
- 同一用途与号码的三把键（验证码、重发间隔、当天计数）在同一个哈希槽，签发、校验与撤销都是一次 Lua 调用。
- 日志只记录号码后 4 位与模板名，不记录验证码与模板参数；AccessKey 只来自环境变量。

## 配置

```yaml
domainforge:
  sms:
    enabled: true
    provider: aliyun                       # 目前只有阿里云实现
    sign-name: ${SERVICE_SMS_SIGN_NAME}
    aliyun:
      access-key-id: ${SERVICE_SMS_ACCESS_KEY_ID}
      access-key-secret: ${SERVICE_SMS_ACCESS_KEY_SECRET}
      # endpoint: https://dysmsapi.aliyuncs.com（默认）
    templates:
      login-code: SMS_123456789           # 逻辑模板名 → 服务商模板编号
  verification:
    enabled: true
    key: ${SERVICE_VERIFICATION_KEY}       # Base64，至少 32 字节
    purposes:
      bind-phone:                          # 用途：小写字母、数字与 -
        template: login-code
        # parameter: code、length: 6、ttl: 5m、max-attempts: 5、resend-interval: 60s、daily-limit: 10
```

启用验证码需要服务接入 cache 组件（会话 Redis）。缺少必需配置时启动失败。

## 服务中的写法

```
application/command/<用例>     调用 VerificationCodes.issue／verify，按结果做业务处理（如绑定手机号）
interfaces/http/<入口>         发送验证码的接口按业务需要加登录、人机验证与网关限流
```

示例（绑定手机号，示意代码，不在模板中）：

```java
/**
 * 绑定手机号：先发验证码，再凭验证码确认。
 */
@Service
public class BindPhone {
    private static final String PURPOSE = "bind-phone"; // 对应 domainforge.verification.purposes.bind-phone
    private final VerificationCodes codes;
    private final AccountRepository accounts;

    BindPhone(VerificationCodes codes, AccountRepository accounts) {
        this.codes = codes;
        this.accounts = accounts;
    }

    // 重发间隔、当天上限、号码无效、服务商频控由组件抛出业务拒绝；服务商或 Redis 不可用为 503。
    public IssuedCode send(String phone) {
        return codes.issue(PURPOSE, phone);
    }

    @Transactional
    public void confirm(long accountId, String phone, String code) {
        if (codes.verify(PURPOSE, phone, code) != VerifyOutcome.VERIFIED) throw new PhoneNotVerified();
        accounts.bindPhone(accountId, phone);
    }
}
```

- 发送验证码的接口是短信费用与骚扰的入口：除组件的同号限制外，业务还应限制同一账号或客户端地址的请求（网关已按客户端地址限流）。
- 校验结果不要区分“号码没有收到过验证码”与“验证码错误”以外的信息，避免枚举号码。

# @@ARTIFACT@@ 的 Nacos 配置种子：dataId 不存在时由 nacos-init 发布一次，之后以 Nacos 中的内容为准，重新部署不覆盖。
# 只放非敏感运行参数；凭据与密钥只来自环境变量。
domainforge:
  config:
    # 来源标记：服务启动时确认配置确实读自 Nacos，缺失即启动失败。
    source: nacos

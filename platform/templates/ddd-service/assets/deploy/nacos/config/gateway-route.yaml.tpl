      - id: @@ARTIFACT@@
        service: @@ARTIFACT@@
        paths: [/api/orders/**, /api/me/orders]
        # 订单凭证上传按账号限流：每分钟补充 30 次、突发 10 次（超出 429 RATE_LIMITED）。
        limits:
          - endpoint: POST /api/orders/*/files
            by: account
            replenish-per-minute: 30
            burst: 10

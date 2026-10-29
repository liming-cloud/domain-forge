      - id: @@ARTIFACT@@
        service: @@ARTIFACT@@
        paths: [/api/notes/**, /api/me/notes]
        # 便笺附件上传按账号限流：每分钟补充 30 次、突发 10 次（超出 429 RATE_LIMITED）。
        limits:
          - endpoint: POST /api/notes/*/files
            by: account
            replenish-per-minute: 30
            burst: 10

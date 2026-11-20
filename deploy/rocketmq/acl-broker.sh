#!/bin/sh
# RocketMQ ACL（compose.mq-acl.yml）开启时 broker 的启动入口：在 broker.conf、proxy.json 上追加认证与授权配置后启动。
# 管理员账号取自环境变量，生成的配置只写在容器内的临时目录；账号与密钥只用字母和数字（写进 JSON 不转义）。
set -eu
: "${ROCKETMQ_ADMIN_ACCESS_KEY:?provide ROCKETMQ_ADMIN_ACCESS_KEY}"
: "${ROCKETMQ_ADMIN_SECRET_KEY:?provide ROCKETMQ_ADMIN_SECRET_KEY}"
conf=/tmp/rocketmq-acl
mkdir -p "$conf"

cp /etc/rocketmq/broker.conf "$conf/broker.conf"
cat >> "$conf/broker.conf" <<EOF
authenticationEnabled=true
authenticationProvider=org.apache.rocketmq.auth.authentication.provider.DefaultAuthenticationProvider
authenticationMetadataProvider=org.apache.rocketmq.auth.authentication.provider.LocalAuthenticationMetadataProvider
authorizationEnabled=true
authorizationProvider=org.apache.rocketmq.auth.authorization.provider.DefaultAuthorizationProvider
authorizationMetadataProvider=org.apache.rocketmq.auth.authorization.provider.LocalAuthorizationMetadataProvider
initAuthenticationUser={"username":"$ROCKETMQ_ADMIN_ACCESS_KEY","password":"$ROCKETMQ_ADMIN_SECRET_KEY"}
innerClientAuthenticationCredentials={"accessKey":"$ROCKETMQ_ADMIN_ACCESS_KEY","secretKey":"$ROCKETMQ_ADMIN_SECRET_KEY"}
EOF

cat > "$conf/proxy.json" <<EOF
{
  "rocketMQClusterName": "DefaultCluster",
  "authenticationEnabled": true,
  "authenticationProvider": "org.apache.rocketmq.auth.authentication.provider.DefaultAuthenticationProvider",
  "authenticationMetadataProvider": "org.apache.rocketmq.proxy.auth.ProxyAuthenticationMetadataProvider",
  "authorizationEnabled": true,
  "authorizationProvider": "org.apache.rocketmq.auth.authorization.provider.DefaultAuthorizationProvider",
  "authorizationMetadataProvider": "org.apache.rocketmq.proxy.auth.ProxyAuthorizationMetadataProvider",
  "innerClientAuthenticationCredentials": "{\"accessKey\":\"$ROCKETMQ_ADMIN_ACCESS_KEY\",\"secretKey\":\"$ROCKETMQ_ADMIN_SECRET_KEY\"}"
}
EOF

exec ./mqbroker --enable-proxy -c "$conf/broker.conf" -pc "$conf/proxy.json"

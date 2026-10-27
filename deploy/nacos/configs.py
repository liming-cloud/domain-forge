#!/usr/bin/env python3
"""导出或导入 Nacos 命名空间中的全部配置，供 backup.sh／restore.sh 调用。

在 Nacos 容器内用 curl 调用管理接口，不对主机发布 Nacos 端口；管理员密码与配置内容只经标准输入传入，
不出现在命令参数中。导出文件为 JSON：[{"dataId", "group", "type", "content"}]。

用法：configs.py export <环境文件> <输出文件>
      configs.py import <环境文件> <导出文件>
部署了业务服务时与 backup.sh 一样设置 COMPOSE_FILE。
"""

import json
import os
from pathlib import Path
import subprocess
import sys

DEPLOY = Path(__file__).resolve().parents[1]
API = 'http://127.0.0.1:8848/nacos/v3'
# 容器内脚本：第一行标准输入是管理员密码，其余是请求体；登录后执行一次请求并输出响应。
REQUEST = r'''
read -r password
body=$(cat)
token=$(curl -sS -X POST "$API/auth/user/login" --data-urlencode "username=nacos" \
    --data-urlencode "password=$password" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
[ -n "$token" ] || { echo "nacos admin login failed" >&2; exit 1; }
if [ "$METHOD" = "GET" ]; then
    curl -sS -f -H "accessToken: $token" "$API$PATH_QUERY"
else
    printf '%s' "$body" | curl -sS -f -H "accessToken: $token" -X POST "$API$PATH_QUERY" \
        --data-urlencode "dataId=$DATA_ID" --data-urlencode "groupName=$GROUP" \
        --data-urlencode "namespaceId=$NAMESPACE" --data-urlencode "type=$TYPE" --data-urlencode "content@-"
fi
'''


def env_value(env_file, key):
    """只读取需要的一项，不把环境文件整体加载到进程环境。"""
    for line in Path(env_file).read_text(encoding='utf-8').splitlines():
        if line.startswith(key + '='):
            return line.split('=', 1)[1]
    raise SystemExit(key + ' 未在环境文件中设置')


def request(env_file, method, path_query, body='', **form):
    compose = ['docker', 'compose']
    if 'COMPOSE_FILE' not in os.environ:
        compose += ['-f', str(DEPLOY / 'compose.yml')]
    variables = {'API': API, 'METHOD': method, 'PATH_QUERY': path_query, 'NAMESPACE': 'public', **form}
    command = compose + ['--env-file', env_file, 'exec', '-T']
    for key, value in variables.items():
        command += ['-e', key + '=' + value]
    command += ['nacos', 'sh', '-c', REQUEST]
    stdin = env_value(env_file, 'NACOS_ADMIN_PASSWORD') + '\n' + body
    result = subprocess.run(command, input=stdin, capture_output=True, text=True, check=False)
    if result.returncode != 0:
        raise SystemExit('Nacos 请求失败：' + method + ' ' + path_query + ' ' + result.stderr.strip())
    return json.loads(result.stdout)


def export(env_file, target):
    page = request(env_file, 'GET', '/admin/cs/config/list?pageNo=1&pageSize=500&namespaceId=public'
                                    '&dataId=&groupName=&search=blur')['data']
    if page['pagesAvailable'] > 1:
        raise SystemExit('配置超过 500 项，需扩展分页')
    items = []
    for item in page['pageItems']:
        query = '/admin/cs/config?dataId={}&groupName={}&namespaceId=public'.format(item['dataId'], item['groupName'])
        detail = request(env_file, 'GET', query)['data']
        items.append({'dataId': detail['dataId'], 'group': detail['groupName'],
                      'type': detail['type'] or 'yaml', 'content': detail['content']})
    old = os.umask(0o077)
    try:
        Path(target).write_text(json.dumps(items, ensure_ascii=False, indent=2), encoding='utf-8')
    finally:
        os.umask(old)
    print('nacos_configs=' + str(len(items)))


def restore(env_file, source):
    items = json.loads(Path(source).read_text(encoding='utf-8'))
    for item in items:
        request(env_file, 'POST', '/admin/cs/config', item['content'],
                DATA_ID=item['dataId'], GROUP=item['group'], TYPE=item['type'])
    print('nacos_configs_restored=' + str(len(items)))


def main():
    if len(sys.argv) != 4 or sys.argv[1] not in ('export', 'import'):
        raise SystemExit(__doc__)
    (export if sys.argv[1] == 'export' else restore)(sys.argv[2], sys.argv[3])


if __name__ == '__main__':
    main()

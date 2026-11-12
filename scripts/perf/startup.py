"""启动耗时基线的计时与汇总，由 startup_baseline.sh 调用（口径见 docs/platform/性能基线.md 的“启动”一节）。

用法：
  python3 -I startup.py measure <容器> <管理端口> <入口地址> <请求路径> <令牌文件> [日志文件]
      启动已停止的容器，输出一行 JSON：容器启动到就绪（秒）、日志中的 Started 秒数、首个请求耗时（毫秒）；
      给出日志文件时把本次启动的容器日志写入其中
  python3 -I startup.py profile <jfr print --json 的输出> <日志文件> <measure 输出的 JSON>
      汇总一次启动（到就绪为止）的耗时分布，输出 Markdown
  python3 -I startup.py report <结果目录>...
      按服务与核数取中位数；给出两个结果目录时比较二者的中位数差异
"""

import json
import re
import statistics
import subprocess
import sys
import time
import urllib.error
import urllib.request
from collections import Counter
from datetime import datetime
from pathlib import Path

POLL_SECONDS = 0.2
TIMEOUT_SECONDS = 300
STARTED = re.compile(r'Started \S+ in (\d+(?:\.\d+)?) seconds')
METRICS = ('ready_seconds', 'started_seconds', 'first_request_ms')


def measure(container: str, port: str, base: str, path: str, token_file: str, log_file: str = '') -> None:
    started = time.time()
    subprocess.run(['docker', 'start', container], check=True, capture_output=True)
    probe = ['docker', 'exec', container, 'curl', '-sf', '-o', '/dev/null',
             f'http://127.0.0.1:{port}/actuator/health/readiness']
    while subprocess.run(probe, capture_output=True).returncode != 0:
        if time.time() - started > TIMEOUT_SECONDS:
            sys.exit(f'{container} 在 {TIMEOUT_SECONDS} 秒内未就绪')
        time.sleep(POLL_SECONDS)
    ready = time.time()
    first, waited = first_request(base + path, Path(token_file).read_text(encoding='utf-8').strip())
    since = datetime.fromtimestamp(started).astimezone().isoformat()
    logs = subprocess.run(['docker', 'logs', '--since', since, container], check=True, capture_output=True, text=True)
    if log_file:
        Path(log_file).write_text(logs.stdout, encoding='utf-8')
    matches = STARTED.findall(logs.stdout + logs.stderr)
    print(json.dumps({'ready_seconds': round(ready - started, 2), 'started_seconds': float(matches[-1]) if matches else None,
                      'first_request_ms': first, 'route_wait_ms': waited, 'started_at': started, 'ready_at': ready}))


def first_request(url: str, token: str) -> tuple:
    """首个到达服务的请求的耗时（毫秒）。网关尚未发现刚重启的实例时返回 502/503，记为路由等待并重试。"""
    began = time.time()
    while True:
        request = urllib.request.Request(url, headers={'Authorization': f'Bearer {token}'})
        sent = time.time()
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                response.read()
                status = response.status
        except urllib.error.HTTPError as error:
            status = error.code
        took = round((time.time() - sent) * 1000)
        if status == 200:
            return took, round((sent - began) * 1000)
        if status not in (502, 503) or time.time() - began > 60:
            sys.exit(f'首个请求 {url} 返回 {status}')
        time.sleep(POLL_SECONDS)


def instant(text: str) -> float:
    """日志与 JFR 的时刻（最多纳秒精度，Z 或时区偏移）换算成 epoch 秒。"""
    match = re.match(r'([^.Z+]+)(?:\.(\d+))?(Z|[+-]\d\d:\d\d)?$', text)
    zone = '+00:00' if match[3] in (None, 'Z') else match[3]
    return datetime.fromisoformat(match[1] + zone).timestamp() + float('0.' + (match[2] or '0'))


def timeline(log_file: str, started_at: float, ready_at: float) -> list:
    """main 线程相邻两条日志之间的墙钟间隔（秒），按长短排序；首尾补上容器启动与就绪两个时刻。"""
    points = [(started_at, '容器启动')]
    for line in Path(log_file).read_text(encoding='utf-8').splitlines():
        try:
            record = json.loads(line)
        except ValueError:
            continue
        if record.get('process', {}).get('thread', {}).get('name') == 'main':
            message = ' '.join(str(record.get('message', '')).split())
            points.append((instant(record['@timestamp']), message[:80]))
    points = [p for p in points if p[0] <= ready_at] + [(ready_at, '就绪检查通过')]
    gaps = [(after[0] - before[0], before[1], after[1]) for before, after in zip(points, points[1:])]
    return sorted(gaps, reverse=True)


# CPU 样本按组件归类：从调用栈的根端起第一个不属于 JDK、Spring 与启动入口（main）的帧，即被调进去的那一处
# （Bean 工厂方法、初始化回调、库自己的线程）；都不是时归 Spring 或 JDK。
CATEGORIES = [('org.flywaydb.', 'Flyway'), ('org.redisson.', 'Redisson'), ('io.netty.', 'Netty'),
              ('com.alibaba.nacos.', 'Nacos'), ('com.alibaba.cloud.', 'Nacos'), ('tech.powerjob.', 'PowerJob'),
              ('io.vertx.', 'PowerJob（Vert.x）'), ('org.h2.', 'PowerJob（H2）'), ('org.apache.rocketmq.', 'RocketMQ'),
              ('org.apache.ibatis.', 'MyBatis'), ('org.mybatis.', 'MyBatis'), ('com.baomidou.', 'MyBatis'),
              ('com.zaxxer.hikari.', 'Hikari／JDBC'), ('org.postgresql.', 'Hikari／JDBC'), ('cn.dev33.', 'Sa-Token'),
              ('org.apache.catalina.', 'Tomcat'), ('org.apache.tomcat.', 'Tomcat'), ('org.apache.coyote.', 'Tomcat'),
              ('com.fasterxml.', 'Jackson'), ('tools.jackson.', 'Jackson'), ('ch.qos.logback.', 'Logback'),
              ('org.hibernate.validator.', 'Hibernate Validator'), ('io.micrometer.', 'Micrometer'),
              ('io.opentelemetry.', 'OpenTelemetry'), ('org.domainforge.', '平台与服务代码'), ('org.example.', '平台与服务代码')]
FRAMEWORK = ('java.', 'javax.', 'jdk.', 'sun.', 'com.sun.', 'jakarta.', 'org.springframework.', 'kotlin.')
# 类加载：类加载器、读取嵌套 jar（根帧 JarLauncher.main 所在的 launch 包不算）。
CLASS_LOADING = ('java.lang.ClassLoader.', 'jdk.internal.loader.', 'org.springframework.boot.loader.jar.',
                 'org.springframework.boot.loader.zip.', 'org.springframework.boot.loader.net.', 'java.util.zip.',
                 'java.util.jar.')


def classify(event: dict) -> tuple:
    stack = (event['values'].get('stackTrace') or {}).get('frames', [])
    names = [f"{f['method']['type']['name'].replace('/', '.')}.{f['method']['name']}" for f in reversed(stack)]
    entry = next((n for n in names if not n.startswith(FRAMEWORK) and not n.endswith('.main')), None)
    if entry is None:
        category = 'Spring（扫描、解析、代理、Bean 装配）' if any(n.startswith('org.springframework.') for n in names) else 'JDK'
    else:
        category = next((c for prefix, c in CATEGORIES if entry.startswith(prefix)), entry.rsplit('.', 2)[0])
    return category, any(n.startswith(CLASS_LOADING) for n in names)


def duration_ms(text: str) -> float:
    """JFR 的时长：jfr print --json 写成 ISO 8601（如 PT0.0123S）。"""
    match = re.match(r'PT(?:(\d+)M)?(\d+(?:\.\d+)?)S', text or '')
    return (int(match[1] or 0) * 60 + float(match[2])) * 1000 if match else 0


def profile(printed: str, log_file: str, run_json: str) -> None:
    run = json.loads(run_json)
    started_at, ready_at = run['started_at'], run['ready_at']
    print(f'本次（带 JFR）：容器启动到就绪 {run["ready_seconds"]} 秒，Started {run["started_seconds"]} 秒。\n')
    print('### 日志时间线：main 线程最长的 10 段\n')
    print('相邻两条 main 线程日志之间的墙钟间隔，即“上一条之后、下一条之前”在做的事。\n')
    print('| 秒 | 上一条 | 下一条 |')
    print('|---|---|---|')
    for gap, before, after in timeline(log_file, started_at, ready_at)[:10]:
        print(f'| {gap:.2f} | {before.replace("|", "／")} | {after.replace("|", "／")} |')

    events = [e for e in json.loads(Path(printed).read_text(encoding='utf-8'))['recording']['events']
              if instant(e['values']['startTime']) <= ready_at]
    samples, loading = Counter(), Counter()
    pauses, collections, classes = 0.0, 0, 0
    for event in events:
        if event['type'] == 'jdk.ExecutionSample':
            category, loads = classify(event)
            samples[category] += 1
            loading[category] += loads
        elif event['type'] == 'jdk.GarbageCollection':
            collections += 1
            pauses += duration_ms(event['values'].get('sumOfPauses'))
        elif event['type'] == 'jdk.ClassLoadingStatistics':
            classes = max(classes, event['values'].get('loadedClassCount', 0))
    total = sum(samples.values()) or 1
    print('\n### JFR：CPU 样本按组件（全部 Java 线程，到就绪为止）\n')
    print(f'样本 {total} 个；GC {collections} 次，停顿合计 {pauses / 1000:.2f} 秒；已加载类 {classes} 个。'
          '占比只表示 Java 代码中的相对分布，不含 JIT 编译、GC 与 JVM 内部的类链接。\n')
    print('| 组件 | 占比 | 其中在类加载与读 jar |')
    print('|---|---|---|')
    for category, count in samples.most_common(10):
        print(f'| {category} | {count / total:.0%} | {loading[category] / total:.0%} |')


def medians(results: Path) -> dict:
    """{(服务, 核数): {指标: 中位数}}；runs.jsonl 每行一次启动。"""
    groups = {}
    for line in (results / 'runs.jsonl').read_text(encoding='utf-8').splitlines():
        run = json.loads(line)
        groups.setdefault((run['service'], run['cpus']), []).append(run)
    return {key: {metric: statistics.median(r[metric] for r in runs) for metric in METRICS} | {'runs': len(runs)}
            for key, runs in groups.items()}


def report(directories: list) -> None:
    rounds = [medians(Path(d)) for d in directories]
    print('| 服务 | CPU（核） | 次数 | 容器启动到就绪（秒） | Started（秒） | 首个请求（ms） |')
    print('|---|---|---|---|---|---|')
    for key, row in sorted(rounds[0].items()):
        print(f'| {key[0]} | {key[1]:g} | {row["runs"]} | {row["ready_seconds"]:.1f} | {row["started_seconds"]:.1f} '
              f'| {row["first_request_ms"]:.0f} |')
    if len(rounds) == 2:
        print('\n两轮中位数差异（第二轮相对第一轮）：\n')
        print('| 服务 | CPU（核） | 容器启动到就绪 | Started | 首个请求 |')
        print('|---|---|---|---|---|')
        for key, first in sorted(rounds[0].items()):
            second = rounds[1][key]
            cells = ' | '.join(f'{(second[m] - first[m]) / first[m]:+.1%}' for m in METRICS)
            print(f'| {key[0]} | {key[1]:g} | {cells} |')


if __name__ == '__main__':
    command, arguments = sys.argv[1], sys.argv[2:]
    if command == 'measure':
        measure(*arguments)
    elif command == 'profile':
        profile(*arguments)
    else:
        report(arguments)

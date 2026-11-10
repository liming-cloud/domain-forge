"""把 k6 的汇总（--summary-export）整理成性能基线的结果表：只取计量阶段，按接口列出吞吐、延迟、错误率与状态码。

用法：python3 -I scripts/perf/report.py <结果目录> <场景...>
"""

import json
import os
import re
import sys
from pathlib import Path

ENDPOINTS = {'login': ['login'], 'query': ['query'], 'submit': ['create', 'submit'], 'digest': ['digest']}
STATUSES = ['0', '200', '409', '429', '503', '504']
# CPU 峰值表列出的容器（docker stats 的 100% 为 1 核），括号内为配额。
CONTAINERS = [('nginx', 0.2), ('gateway', 0.6), ('iam', 0.8), ('order-service', 0.6), ('board-service', 0.2),
              ('postgres', 0.8), ('redis', 0.2), ('rocketmq-broker', 0.2)]


def seconds(text: str) -> float:
    """k6 的时长写法（如 3m、90s、1m30s）换算成秒。"""
    units = {'h': 3600, 'm': 60, 's': 1}
    return sum(float(n) * units[u] for n, u in re.findall(r'(\d+(?:\.\d+)?)([hms])', text))


def row(metrics: dict, name: str, duration: float) -> str:
    def metric(base: str, *tags: str) -> dict:
        return metrics.get(f'{base}{{{",".join(("phase:measure", f"name:{name}") + tags)}}}', {})

    latency = metric('http_req_duration')
    failed = metric('http_req_failed')
    total = failed.get('passes', 0) + failed.get('fails', 0)
    statuses = ' '.join(f'{s}:{metric("http_reqs", f"status:{s}").get("count", 0)}' for s in STATUSES)
    other = total - sum(metric('http_reqs', f'status:{s}').get('count', 0) for s in STATUSES)
    return (f'| {name} | {total / duration:.1f} | {latency.get("p(50)", 0):.0f} | {latency.get("p(95)", 0):.0f} '
            f'| {latency.get("p(99)", 0):.0f} | {failed.get("value", 0):.2%} | {statuses} 其他:{other} |')


def main() -> None:
    results, scenarios = Path(sys.argv[1]), sys.argv[2:]
    print('| 接口 | 吞吐（次/秒） | P50（ms） | P95（ms） | P99（ms） | 错误率 | 状态码 |')
    print('|---|---|---|---|---|---|---|')
    notes = []
    duration = seconds(os.environ.get('DURATION', '3m'))
    for scenario in scenarios:
        metrics = json.loads((results / f'{scenario}.json').read_text(encoding='utf-8'))['metrics']
        for name in ENDPOINTS[scenario]:
            print(row(metrics, name, duration))
        planned = int(float(os.environ.get('RATE', '200')) * duration)
        dropped = planned - metrics.get('iterations{phase:measure}', {}).get('count', 0)
        sessionless = metrics.get('sessionless{phase:measure}', {}).get('count', 0)
        # 边界上差一两次属于计时误差。
        if dropped > planned * 0.001 or sessionless:
            notes.append(f'- {scenario}：计量阶段应发 {planned} 次迭代，未发出 {dropped}（虚拟用户不够），没有令牌 {sessionless}')
    print('\n'.join(notes))
    print()
    print('| 场景 | ' + ' | '.join(f'{c}（{q} 核）' for c, q in CONTAINERS) + ' |')
    print('|---|' + '---|' * len(CONTAINERS))
    for scenario in scenarios:
        print(f'| {scenario} | ' + ' | '.join(f'{peaks(results / f"stats-{scenario}.txt").get(c, 0):.0%}'
                                            for c, _ in CONTAINERS) + ' |')


def peaks(stats: Path) -> dict:
    """压测期间每个容器 CPU 采样的最大值（1.0 为 1 核）；采样行形如“gateway-1 17.95% 327MiB / 768MiB”。"""
    top = {}
    for line in stats.read_text(encoding='utf-8').splitlines():
        match = re.match(r'(\S+)-\d+ (\d+(?:\.\d+)?)%', line)
        if match:
            top[match[1]] = max(top.get(match[1], 0), float(match[2]) / 100)
    return top


if __name__ == '__main__':
    main()

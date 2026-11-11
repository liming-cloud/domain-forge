// 灌数与压测共用的请求与账号约定（见 docs/platform/性能基线.md）。
import http from 'k6/http';
import { sleep } from 'k6';

export const BASE = __ENV.BASE || 'http://nginx';
export const PASSWORD = __ENV.PASSWORD;
export const ACCOUNTS = Number(__ENV.ACCOUNTS || 10000);
// 虚拟用户数不超过 k6.sh 的 480 个源地址。
export const VUS = Number(__ENV.VUS || 480);

// 第 i 个压测账号的登录名（1 起）。
export function accountName(i) {
    return `perf${String(i).padStart(5, '0')}`;
}

// 一行订单：两件、单价 15 元；sku 随序号变化，同一订单内不重复。
export function orderLine(n) {
    return { sku: `SKU-${n}`, quantity: 2, unitPriceCents: 1500 };
}

// 固定速率下每次迭代至少持续的秒数。k6 总是复用刚空闲的虚拟用户，响应快时少数几个虚拟用户（即少数源地址）
// 就能接住全部请求；拉长迭代（不足部分休眠，不计入请求耗时）让同时在用的虚拟用户约为 VUS 的 95%。
// 默认 480 个虚拟用户、每秒 200 次时约 2.3 秒，每个源地址每分钟约 26 次，低于按地址的登录保护（每分钟 30 次）。
export function pacing(rate, vus = VUS) {
    return (0.95 * vus) / rate;
}

// 执行 body，再休眠到本次迭代满 seconds 秒。
export function paced(seconds, body) {
    const started = Date.now();
    body();
    sleep(Math.max(0, seconds - (Date.now() - started) / 1000));
}

// 发一次请求，不重试；name 标签区分接口，结果按它统计。
export function request(method, path, body, token, name, headers) {
    const params = { headers: Object.assign({ 'Content-Type': 'application/json' }, headers), tags: { name } };
    if (token) params.headers.Authorization = `Bearer ${token}`;
    return http.request(method, BASE + path, body === undefined ? null : JSON.stringify(body), params);
}

// 灌数用：429、503（限流、密码计算繁忙）与连接失败（状态 0）退避重试，最多 attempts 次；其他失败或重试用尽时抛出，
// 让整次灌数失败。
export function must(method, path, body, token, name, attempts = 30) {
    for (let attempt = 1; ; attempt++) {
        const res = request(method, path, body, token, name);
        if (res.status >= 200 && res.status < 300) return res;
        if ([0, 429, 503].includes(res.status) && attempt < attempts) {
            sleep(Math.min(2 * attempt, 10));
            continue;
        }
        throw new Error(`${method} ${path}：${res.status} ${res.body}`);
    }
}

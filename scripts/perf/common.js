// 灌数与压测共用的请求与账号约定（见 docs/platform/性能基线.md）。
import http from 'k6/http';
import { sleep } from 'k6';

export const BASE = __ENV.BASE || 'http://nginx';
export const PASSWORD = __ENV.PASSWORD;
export const ACCOUNTS = Number(__ENV.ACCOUNTS || 10000);

// 第 i 个压测账号的登录名（1 起）。
export function accountName(i) {
    return `perf${String(i).padStart(5, '0')}`;
}

// 一行订单：两件、单价 15 元；sku 随序号变化，同一订单内不重复。
export function orderLine(n) {
    return { sku: `SKU-${n}`, quantity: 2, unitPriceCents: 1500 };
}

// 发一次请求，不重试；name 标签区分接口，结果按它统计。
export function request(method, path, body, token, name, headers) {
    const params = { headers: Object.assign({ 'Content-Type': 'application/json' }, headers), tags: { name } };
    if (token) params.headers.Authorization = `Bearer ${token}`;
    return http.request(method, BASE + path, body === undefined ? null : JSON.stringify(body), params);
}

// 灌数用：429、503（限流、密码计算排队已满）退避重试，其他失败抛出，让该迭代与整次灌数失败。
export function must(method, path, body, token, name) {
    for (let attempt = 1; ; attempt++) {
        const res = request(method, path, body, token, name);
        if (res.status >= 200 && res.status < 300) return res;
        if ((res.status === 429 || res.status === 503) && attempt < 60) {
            sleep(Math.min(attempt, 5));
            continue;
        }
        throw new Error(`${method} ${path}：${res.status} ${res.body}`);
    }
}

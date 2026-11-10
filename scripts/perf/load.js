// 压测场景：每次运行只压一个场景（SCENARIO），以同一速率连续运行 WARMUP 加 DURATION；前 WARMUP 为预热，
// 之后的迭代带 phase:measure 标签，只统计这部分。预热与计量用同一批虚拟用户，预热期间取得的令牌在计量时继续使用。
//   login  登录（每次随机一个账号）
//   query  带数据范围的订单查询：GET /api/orders（本人范围，每个账号 10 张）
//   submit 提交订单：新建草稿（create）后带 Idempotency-Key 提交（submit，同事务写 Outbox）；每次迭代两次请求
//   digest 跨服务读取订单摘要：board-service 以同一用户身份调用 order-service 的 GET /api/orders
// 除 login 外，每个虚拟用户固定使用一个账号，首次迭代登录后复用令牌（登录不计入该场景）。
// 环境变量：SCENARIO、PASSWORD，可选 RATE（每秒迭代数，默认 200）、WARMUP（默认 2m）、DURATION（默认 3m）、
// P95_MS 与 ERROR_RATE（门槛，默认 200 与 0.001）、VUS（默认 1000，不超过 k6 的源地址数）。
import exec from 'k6/execution';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { ACCOUNTS, PASSWORD, accountName, orderLine, request } from './common.js';

const SCENARIO = __ENV.SCENARIO;
const RATE = Number(__ENV.RATE || 200);
const WARMUP = __ENV.WARMUP || '2m';
const DURATION = __ENV.DURATION || '3m';
const VUS = Number(__ENV.VUS || 1000);
const ENDPOINTS = { login: ['login'], query: ['query'], submit: ['create', 'submit'], digest: ['digest'] };
if (!ENDPOINTS[SCENARIO]) throw new Error(`SCENARIO 须为 ${Object.keys(ENDPOINTS).join('、')} 之一`);

// k6 时长写法（如 2m、90s、1m30s）换算成毫秒。
const millis = (text) => [...text.matchAll(/(\d+(?:\.\d+)?)([hms])/g)]
    .reduce((sum, [, n, unit]) => sum + Number(n) * { h: 3600000, m: 60000, s: 1000 }[unit], 0);
const WARMUP_MS = millis(WARMUP);

const thresholds = {};
for (const name of ENDPOINTS[SCENARIO]) {
    thresholds[`http_req_duration{phase:measure,name:${name}}`] = [`p(95)<${__ENV.P95_MS || 200}`];
    thresholds[`http_req_failed{phase:measure,name:${name}}`] = [`rate<${__ENV.ERROR_RATE || 0.001}`];
    // 只为在汇总中按状态码列出请求数，不作判断。
    for (const status of [200, 409, 429, 503]) {
        thresholds[`http_reqs{phase:measure,name:${name},status:${status}}`] = ['count>=0'];
    }
}
// 计量阶段没有令牌的迭代算未达标；计量阶段的迭代数列入汇总，与速率乘时长之差即虚拟用户不够而没有发出的迭代。
thresholds['sessionless{phase:measure}'] = ['count==0'];
thresholds['iterations{phase:measure}'] = ['count>=0'];

export const options = {
    scenarios: {
        // 预分配足够多的虚拟用户：空闲的虚拟用户按先进先出轮流取用，请求均匀分布到各源地址。
        load: {
            executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s',
            duration: `${(WARMUP_MS + millis(DURATION)) / 1000}s`, preAllocatedVUs: VUS, maxVUs: VUS, exec: SCENARIO,
        },
    },
    thresholds,
    summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

const sessionless = new Counter('sessionless');
let token;

// 每次迭代至少持续 PACE 秒（不足部分 sleep，不计入请求耗时）。k6 总是复用刚空闲的虚拟用户，响应快时少数几个
// 虚拟用户（即少数源地址）就能接住全部请求；拉长迭代让同时在用的虚拟用户约为 VUS 的八成，每个源地址每 PACE 秒一次。
const PACE = (0.8 * VUS) / RATE;

// 包装场景函数：按已运行时间给本次迭代的全部指标打上阶段标签，并补足节拍。
function paced(body) {
    return () => {
        const started = Date.now();
        exec.vu.metrics.tags.phase = started - exec.scenario.startTime < WARMUP_MS ? 'warmup' : 'measure';
        body();
        sleep(Math.max(0, PACE - (Date.now() - started) / 1000));
    };
}

// 本虚拟用户的账号令牌：首次调用时登录（失败则本次迭代放弃，下次再试）。
function session() {
    if (token) return token;
    const loginName = accountName(((exec.vu.idInTest - 1) % ACCOUNTS) + 1);
    const res = request('POST', '/api/auth/login', { loginName, password: PASSWORD }, null, 'session');
    if (res.status === 200) token = res.json('token');
    else sessionless.add(1);
    return token;
}

export const login = paced(() => {
    const loginName = accountName(Math.floor(Math.random() * ACCOUNTS) + 1);
    const res = request('POST', '/api/auth/login', { loginName, password: PASSWORD }, null, 'login');
    check(res, { 'login 200': (r) => r.status === 200 });
});

export const query = paced(() => {
    if (!session()) return;
    const res = request('GET', '/api/orders?limit=20', undefined, token, 'query');
    check(res, { 'query 200': (r) => r.status === 200 });
});

export const submit = paced(() => {
    if (!session()) return;
    const created = request('POST', '/api/orders', { lines: [orderLine(0)] }, token, 'create');
    if (!check(created, { 'create 200': (r) => r.status === 200 })) return;
    const key = `perf-${exec.vu.idInTest}-${exec.vu.iterationInInstance}-${Date.now()}`;
    const res = request('POST', `/api/orders/${created.json('id')}/submit`, { expectedVersion: 1 }, token, 'submit',
        { 'Idempotency-Key': key });
    check(res, { 'submit 200': (r) => r.status === 200 });
});

export const digest = paced(() => {
    if (!session()) return;
    const res = request('GET', '/api/board/order-digest', undefined, token, 'digest');
    check(res, { 'digest 200': (r) => r.status === 200 });
});

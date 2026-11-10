// 压测数据：ACCOUNTS 个账号（邀请、注册、分配订单角色），每个账号以本人身份新建 ORDERS_PER_ACCOUNT 张草稿订单。
// 全部经 Nginx 与网关调用公开接口，不直接写库。环境变量：ADMIN_TOKEN、ROLE_ID、PASSWORD，
// 可选 ACCOUNTS、ORDERS_PER_ACCOUNT、SEED_RATE（每秒灌入的账号数，默认 12）。
//
// 每个账号要做两次密码计算（注册、登录），IAM 的密码计算池只有 2 个线程加 8 个排队位，超出即拒绝；
// 失败的登录还会计入账号的失败次数（10 分钟 5 次），所以按固定速率灌入而不是尽快灌入，登录最多试 4 次。
// 灌数时还没有施加 CPU 配额（见 run_baseline.sh），默认速率按此确定。
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { ACCOUNTS, PASSWORD, VUS, accountName, must, orderLine, paced, pacing } from './common.js';

const ORDERS = Number(__ENV.ORDERS_PER_ACCOUNT || 10);
const RATE = Number(__ENV.SEED_RATE || 12);
// 灌数的虚拟用户数：迭代节拍（0.8 × 虚拟用户数 ÷ 速率，约 17 秒）须短于 Nginx 的长连接超时（约 65 秒），
// 否则每次迭代都从闲置已久的源地址重新建连，邻居表项已过期，连接常常失败。
const SEED_VUS = Math.min(256, VUS);
const seeded = new Counter('seeded');

export const options = {
    scenarios: {
        seed: {
            executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: `${Math.ceil(ACCOUNTS / RATE)}s`,
            preAllocatedVUs: SEED_VUS, maxVUs: SEED_VUS,
        },
    },
    // 每个账号都灌完才算成功（按时长发起的迭代可能比账号数多一两次，多出的不做事）。
    thresholds: { seeded: [`count==${ACCOUNTS}`] },
    summaryTrendStats: ['avg', 'p(95)', 'max'],
};

export default function () {
    const i = exec.scenario.iterationInTest + 1;
    if (i > ACCOUNTS) return;
    paced(pacing(RATE, SEED_VUS), () => {
        const admin = __ENV.ADMIN_TOKEN;
        const loginName = accountName(i);
        const secret = must('POST', '/api/invitations', { loginName }, admin, 'invite').json('secret');
        const account = must('POST', '/api/auth/invitations/accept',
            { invitationSecret: secret, loginName, password: PASSWORD }, null, 'accept').json();
        must('PUT', `/api/accounts/${account.id}/roles`,
            { expectedVersion: account.version, roleIds: [__ENV.ROLE_ID] }, admin, 'assign');
        const token = must('POST', '/api/auth/login', { loginName, password: PASSWORD }, null, 'login', 4)
            .json('token');
        for (let n = 0; n < ORDERS; n++) must('POST', '/api/orders', { lines: [orderLine(n)] }, token, 'create');
        // 压测时第 i 个虚拟用户使用第 i 个账号：前 VUS 个账号的令牌经 k6 的控制台输出交给 load.js（会话 8 小时）。
        if (i <= VUS) console.log(`token ${i} ${token}`);
        seeded.add(1);
    });
}

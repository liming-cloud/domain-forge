// 压测数据：ACCOUNTS 个账号（邀请、注册、分配订单角色），每个账号以本人身份新建 ORDERS_PER_ACCOUNT 张草稿订单。
// 全部经 Nginx 与网关调用公开接口，不直接写库。环境变量：ADMIN_TOKEN、ROLE_ID、PASSWORD，可选 ACCOUNTS、ORDERS_PER_ACCOUNT。
import exec from 'k6/execution';
import { ACCOUNTS, PASSWORD, accountName, must, orderLine } from './common.js';

const ORDERS = Number(__ENV.ORDERS_PER_ACCOUNT || 10);

export const options = {
    scenarios: {
        seed: { executor: 'shared-iterations', vus: Math.min(256, ACCOUNTS), iterations: ACCOUNTS, maxDuration: '2h' },
    },
    // 任一账号没有灌完即整次失败。
    thresholds: { iterations: [`count==${ACCOUNTS}`] },
    summaryTrendStats: ['avg', 'p(95)', 'max'],
};

export default function () {
    const admin = __ENV.ADMIN_TOKEN;
    const loginName = accountName(exec.scenario.iterationInTest + 1);
    const secret = must('POST', '/api/invitations', { loginName }, admin, 'invite').json('secret');
    const account = must('POST', '/api/auth/invitations/accept',
        { invitationSecret: secret, loginName, password: PASSWORD }, null, 'accept').json();
    must('PUT', `/api/accounts/${account.id}/roles`,
        { expectedVersion: account.version, roleIds: [__ENV.ROLE_ID] }, admin, 'assign');
    const token = must('POST', '/api/auth/login', { loginName, password: PASSWORD }, null, 'login').json('token');
    for (let n = 0; n < ORDERS; n++) must('POST', '/api/orders', { lines: [orderLine(n)] }, token, 'create');
}

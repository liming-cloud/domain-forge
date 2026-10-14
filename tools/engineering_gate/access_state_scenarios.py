"""访问适配的固定独立场景；期望不由被测响应或当前数据库反推。"""

import concurrent.futures
import contextlib
import json
import pathlib
import re
import time
import uuid

from calibrate_source import require
from calibrate_access_state import PASSWORD, CHANGED


def authorization(runtime):
    token = runtime.login("a")
    bob = runtime.login("b", 3)
    runtime.request("b", "GET", "/gate/identity", token=token)
    listed = runtime.request("a", "GET", "/gate/resources?owner=3&scope=20", token=token)
    require(listed == [11], "SG_ACCESS_STATE_RESOURCE", "列表必须同时应用可信归属和范围，客户端字段不能扩大数据")
    allowed = runtime.request("b", "GET", "/gate/resource/11", token=token)
    require(allowed == {"id": 11, "value": "seed-11"}, "SG_ACCESS_STATE_RESOURCE", "成功路径须读取独立合成资源")
    for identifier in (12, 21):
        runtime.request("a", "GET", "/gate/resource/" + str(identifier), 403, token)
        runtime.request("b", "POST", "/gate/resource/" + str(identifier), 403, token, {"value": "forbidden", "owner": "2", "scope": "10"})
    runtime.request("b", "POST", "/gate/resource/12", 403, bob, {"value": "forbidden"})
    value = uuid.uuid4().hex
    runtime.request("a", "POST", "/gate/resource/11", token=token, body={"value": value, "owner": "3"})
    actual = runtime.sql("SELECT id||':'||value FROM gate_resource WHERE project='alpha' ORDER BY id;")
    require(actual == "11:" + value + "\n12:seed-12\n21:seed-21", "SG_ACCESS_STATE_DATA", "授权成功须实际提交，拒绝请求不得写入范围外数据")
    runtime.request("a", "GET", "/gate/resource/11", 401)


def identity(runtime, node, token, status=200, identifier=2):
    data = runtime.request(node, "GET", "/gate/identity", status, token)
    require(status != 200 or data == {"id": identifier}, "SG_ACCESS_STATE_IDENTITY", "可信身份必须来自实际会话")


def session(runtime):
    token, other = runtime.login("a"), runtime.login("c")
    identity(runtime, "b", token)
    identity(runtime, "c", token, 401)
    identity(runtime, "a", other, 401)
    key = "Authorization:login:token:" + token
    require(runtime.redis("GET", key) == "2" and runtime.redis("EXISTS", key, database=1) == "0",
            "SG_ACCESS_STATE_SESSION", "原生令牌须真实保存于所属 Redis 项目库")
    original = runtime.nodes["a"]["pid"]
    runtime.stop("a")
    runtime.start("a")
    require(runtime.nodes["a"]["pid"] != original, "SG_ACCESS_STATE_SESSION", "重启须实际更换进程")
    identity(runtime, "a", token)
    runtime.request("b", "POST", "/gate/logout", token=token)
    identity(runtime, "a", token, 401)
    require(runtime.redis("EXISTS", key) == "0", "SG_ACCESS_STATE_SESSION", "登出须真实删除原生令牌映射")
    identity(runtime, "c", other)


def expiration(runtime):
    token = runtime.login("a")
    identity(runtime, "b", token)
    ttl = int(runtime.redis("TTL", "Authorization:login:token:" + token))
    require(0 <= ttl <= 1, "SG_ACCESS_STATE_SESSION", "合成短会话必须使用真实 Redis 过期")
    time.sleep(1.15)
    identity(runtime, "a", token, 401)
    identity(runtime, "b", token, 401)
    fresh = runtime.login("b")
    identity(runtime, "a", fresh)


def change(runtime, token, subject, field, value, expected=200):
    data = runtime.request("a", "POST", "/gate/admin/state", expected, token,
                           {"id": subject, "field": field, "value": value, "actor": 999})
    if expected == 200:
        require(isinstance(data, dict) and set(data) == {"business", "audit", "event"} and data["business"] == "complete" and
                isinstance(data["event"], str) and re.fullmatch(r"[0-9a-f-]{36}", data["event"]),
                "SG_ACCESS_STATE_AUDIT", "变更与审计结果须分别表达，不接受自报完整成功")
        columns = {"active": "active", "permissions": "permissions", "roles": "roles", "scope": "scope"}
        actual = runtime.sql("SELECT " + columns[field] + "::text FROM gate_account WHERE project='alpha' AND id=" + str(subject) + ";")
        require(actual == value, "SG_ACCESS_STATE_DATA", "报告完成的每次状态变更须立即有独立数据库行值证据")
    return data


def changes(runtime):
    token, admin, other = runtime.login("b"), runtime.login("a", 1), runtime.login("c")
    for node in ("a", "b"):
        identity(runtime, node, token)
        runtime.request(node, "GET", "/gate/resource/11", token=token)
    change(runtime, admin, 2, "active", "false")
    require(runtime.sql("SELECT active FROM gate_account WHERE project='alpha' AND id=2;") == "f", "SG_ACCESS_STATE_DATA", "禁用须实际提交")
    for node in ("a", "b"):
        identity(runtime, node, token, 401)
    change(runtime, admin, 2, "active", "true")
    change(runtime, admin, 2, "permissions", "")
    for node in ("a", "b"):
        identity(runtime, node, token)
        runtime.request(node, "GET", "/gate/resource/11", 403, token)
    change(runtime, admin, 2, "permissions", "read,write")
    change(runtime, admin, 2, "scope", "0")
    for node in ("a", "b"):
        runtime.request(node, "GET", "/gate/resource/11", 403, token)
        require(runtime.request(node, "GET", "/gate/resources", token=token) == [], "SG_ACCESS_STATE_RESOURCE", "缩小范围须立即影响列表")
    identity(runtime, "c", other)
    change(runtime, admin, 1, "roles", "")
    for node in ("a", "b"):
        runtime.request(node, "POST", "/gate/admin/state", 403, admin, {"id": 2, "field": "scope", "value": "10", "actor": 1})
    require(runtime.sql("SELECT scope FROM gate_account WHERE project='alpha' AND id=2;") == "0", "SG_ACCESS_STATE_DATA", "角色撤销后不得继续管理写入")


def password(runtime):
    first, second = runtime.login("a"), runtime.login("b")
    bob, other = runtime.login("b", 3), runtime.login("c")
    require(first != second, "SG_ACCESS_STATE_SESSION", "必须建立两个不同旧会话")
    before = runtime.sql("SELECT hash FROM gate_account WHERE project='alpha' AND id=2;")
    data = runtime.request("a", "POST", "/gate/password", token=first, body={"oldPassword": PASSWORD, "newPassword": CHANGED})
    require(data.get("business") == "complete" and data.get("audit") == "saved", "SG_ACCESS_STATE_CHANGE", "密码条件完整后才能报告成功")
    after = runtime.sql("SELECT hash FROM gate_account WHERE project='alpha' AND id=2;")
    require(after != before and after.startswith("$argon2id$"), "SG_ACCESS_STATE_PASSWORD", "新密码须真实保存为成熟摘要")
    for token in (first, second):
        for node in ("a", "b"):
            identity(runtime, node, token, 401)
    runtime.login("b", password=PASSWORD, expected=401)
    fresh = runtime.login("b", password=CHANGED)
    identity(runtime, "a", fresh)
    identity(runtime, "a", bob, identifier=3)
    identity(runtime, "c", other)


@contextlib.contextmanager
def paused_redis(runtime):
    import access_state_environment
    import calibrate_flyway
    access_state_environment.connections(pathlib.Path(runtime.args.connections))
    container = runtime.credentials["redis"]["container"]
    calibrate_flyway.docker("pause", container, endpoint=runtime.endpoint)
    try:
        yield
    finally:
        calibrate_flyway.docker("unpause", container, endpoint=runtime.endpoint)


def failures(runtime):
    token = runtime.login("a")
    with paused_redis(runtime):
        identity(runtime, "b", token, 503)
        runtime.login("a", expected=503)
    identity(runtime, "b", token)
    runtime.sql("ALTER TABLE gate_account RENAME TO gate_account_unavailable;")
    try:
        identity(runtime, "a", token, 503)
        runtime.request("b", "GET", "/gate/resource/11", 503, token)
    finally:
        runtime.sql("ALTER TABLE gate_account_unavailable RENAME TO gate_account;")
    identity(runtime, "a", token)
    password_failure(runtime, token)


def password_failure(runtime, token):
    runtime.sql("CREATE FUNCTION gate_delay() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.hash <> OLD.hash THEN PERFORM pg_sleep(1.2); END IF; RETURN NEW; END $$; CREATE TRIGGER gate_delay BEFORE UPDATE ON gate_account FOR EACH ROW EXECUTE FUNCTION gate_delay();")
    try:
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
            request = pool.submit(runtime.request, "a", "POST", "/gate/password", 503, token, {"oldPassword": PASSWORD, "newPassword": CHANGED})
            deadline = time.monotonic() + 3
            while time.monotonic() < deadline:
                if runtime.sql("SELECT count(*) FROM pg_stat_activity WHERE wait_event='PgSleep' AND query LIKE 'UPDATE gate_account SET hash%';") == "1":
                    break
                time.sleep(0.03)
            else:
                require(False, "SG_ACCESS_STATE_FAULT", "须观察真实密码写入后再触发 Redis 故障")
            with paused_redis(runtime):
                request.result(timeout=5)
    finally:
        runtime.sql("DROP TRIGGER gate_delay ON gate_account; DROP FUNCTION gate_delay();")
    require(runtime.sql("SELECT revision FROM gate_account WHERE project='alpha' AND id=2;") == "1", "SG_ACCESS_STATE_FAULT", "故障发生前数据库变更须真实提交")
    require(runtime.sql("SELECT count(*) FROM gate_audit WHERE action='password';") == "0", "SG_ACCESS_STATE_CHANGE", "撤销未完成不能冒充已完成审计")
    identity(runtime, "a", token, 401)
    identity(runtime, "b", token, 401)
    runtime.login("b", password=PASSWORD, expected=401)
    fresh = runtime.login("b", password=CHANGED)
    identity(runtime, "a", fresh)


def throttle(runtime):
    for node in ("a", "b", "a"):
        runtime.login(node, password="wrong", expected=401)
    require(int(runtime.redis("GET", "alpha:fail:2")) == 3, "SG_ACCESS_STATE_LIMIT", "限制必须绑定真实登录路径和共享原子计数")
    runtime.login("b", expected=429)
    identity(runtime, "c", runtime.login("c"))
    identity(runtime, "b", runtime.login("a", 3), identifier=3)
    time.sleep(2.1)
    runtime.login("b", password="wrong", expected=401)
    identity(runtime, "b", runtime.login("a"))
    require(runtime.redis("EXISTS", "alpha:fail:2") == "0", "SG_ACCESS_STATE_LIMIT", "合法成功后的恢复须清理本账号计数")
    runtime.stop("a")
    runtime.stop("b")
    runtime.start("a", GATE_LOGIN_LIMIT="2")
    runtime.start("b", GATE_LOGIN_LIMIT="2")
    runtime.login("a", password="wrong", expected=401)
    runtime.login("b", password="wrong", expected=401)
    runtime.login("a", expected=429)
    time.sleep(2.1)
    start = len(runtime.interactions)
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        results = [pool.submit(runtime.login, "a" if index % 2 else "b", password="wrong", expected=(401, 429)) for index in range(6)]
        for result in results:
            result.result(timeout=5)
    require(sorted(item["status"] for item in runtime.interactions[start:]) == [401, 401, 429, 429, 429, 429],
            "SG_ACCESS_STATE_LIMIT", "两实例并发尝试仍须遵守同一原子限额")
    runtime.login("b", expected=429)


def audit_row(runtime, event):
    require(isinstance(event, str) and re.fullmatch(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", event),
            "SG_ACCESS_STATE_AUDIT", "审计响应只能提供事件 UUID，不能成为任意 SQL")
    value = runtime.sql("SELECT row_to_json(a)::text FROM gate_audit a WHERE event='" + event + "'::uuid;")
    require(bool(value), "SG_ACCESS_STATE_AUDIT", "声称审计已保存时须有独立可读取记录")
    return json.loads(value)


def audit(runtime):
    token, admin = runtime.login("b"), runtime.login("a", 1)
    change(runtime, token, 2, "active", "false", 403)
    require(runtime.sql("SELECT active FROM gate_account WHERE project='alpha' AND id=2;") == "t", "SG_ACCESS_STATE_DATA", "无权限写入不能借审计容错通过")
    normal = change(runtime, admin, 2, "permissions", "read")
    row = audit_row(runtime, normal["event"])
    require(normal["audit"] == "saved" and {key: row[key] for key in ("project", "actor", "subject", "action", "result", "partial", "source")} ==
            {"project": "alpha", "actor": 1, "subject": 2, "action": "permissions", "result": "changed", "partial": False, "source": "completed-operation"},
            "SG_ACCESS_STATE_AUDIT", "审计须记录可信主体、对象、动作和实际结果")
    require(runtime.sql("SELECT happened_at <= clock_timestamp() AND happened_at > clock_timestamp()-interval '1 minute' FROM gate_audit;") == "t", "SG_ACCESS_STATE_AUDIT", "实际发生时间须可核对")
    runtime.sql("ALTER TABLE gate_audit RENAME TO gate_audit_unavailable;")
    try:
        missing = change(runtime, admin, 2, "active", "false")
        require(missing["audit"] == "missing" and runtime.sql("SELECT active FROM gate_account WHERE project='alpha' AND id=2;") == "f",
                "SG_ACCESS_STATE_AUDIT", "单独审计故障不能回滚已完成变更或伪报已保存")
        identity(runtime, "b", token, 401)
        require("GATE_AUDIT_FAILED:" + missing["event"] in runtime.output(), "SG_ACCESS_STATE_AUDIT", "审计失败须有独立于失效写入链路的告警")
    finally:
        runtime.sql("ALTER TABLE gate_audit_unavailable RENAME TO gate_audit;")
    require(runtime.sql("SELECT count(*) FROM gate_audit WHERE event='" + missing["event"] + "';") == "0", "SG_ACCESS_STATE_AUDIT", "不得伪造已丢失的原始审计")
    repaired = runtime.request("a", "POST", "/gate/admin/reconcile", token=admin, body={"id": 2, "field": "", "value": "", "actor": 999})
    restored = audit_row(runtime, repaired["event"])
    require(restored["action"] == "manual-snapshot" and restored["source"] == "current-database" and restored["partial"] is True and
            restored["actor"] == 1 and restored["subject"] == 2 and restored["result"] == "active=false;scope=10;revision=0" and
            restored["original_actor"] is None and restored["original_time"] is None and repaired["event"] != missing["event"],
            "SG_ACCESS_STATE_AUDIT", "补录只能保留当前可核对事实，原始缺失信息必须保持未知")


def run(runtime, profile):
    runtime.material.extend(runtime.sql("SELECT hash FROM gate_account ORDER BY project,id;").splitlines())
    {"authorization": authorization, "session": session, "expiration": expiration, "changes": changes,
     "password": password, "failures": failures, "throttle": throttle, "audit": audit}[profile](runtime)
